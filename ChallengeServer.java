package challenge;

import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.charset.*;
import java.security.*;

import com.google.common.util.concurrent.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;

import org.bitcoinj.utils.*;
import org.bitcoinj.core.*;
import org.bitcoinj.script.*;
import org.bitcoinj.params.*;
import org.bitcoinj.wallet.*;
import org.bitcoinj.kits.*;

class Util
{
  static final Charset UTF_8 = Charset.forName("UTF-8");
  
  static String slurp(InputStream inp)
  {
    try {
      StringBuilder out = (new StringBuilder());
      
      byte[] buf = (new byte[512]);
      
      int amt;
      
      while ((amt = inp.read(buf)) >= 0) {
        out.append((new String(buf, 0, amt, UTF_8)));
      }
      
      return out.toString();
    } catch (IOException e) {
      throw (new RuntimeException(e));
    }
  }
  
  static final char[] nibble = (new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' });
  
  static String hexdigest(String body)
  {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      
      md.update(body.getBytes(UTF_8));
      
      byte[] digest = md.digest();
      
      StringBuilder out = (new StringBuilder());
      
      for (byte x : digest) {
        out.append(nibble[((x >> 4) & 0x0F)]);
        out.append(nibble[((x     ) & 0x0F)]);
      }
      
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw (new RuntimeException(e));
    }
  }
}

public class ChallengeServer
{
  static final SecureRandom secureRandom = (new SecureRandom());
  
  static String secureRandomToken()
  {
    StringBuilder o = (new StringBuilder());
    
    for (int i = 0; i < 64; i++) {
      o.append(Util.nibble[secureRandom.nextInt(16)]);
    }
    
    return o.toString();
  }
  
  static class ChallengeProcess
  {
    final String amount;
    final String sender;
    final String recipient;
    final String routingnr;
    final String accountnr;
    final String attachment;
    
    ChallengeProcess(String amount, String sender, String recipient, String routingnr, String accountnr, String attachment)
    {
      this.amount = amount;
      this.sender = sender;
      this.recipient = recipient;
      this.routingnr = routingnr;
      this.accountnr = accountnr;
      this.attachment = attachment;
    }
    
    boolean submitted = false;
    String  txid;
    
    boolean confirmed = false;
    
    synchronized boolean getSubmitted() { return submitted; }
    synchronized String  getTxid() { return txid; }
    synchronized void    setSubmitted(String txid) { submitted = true; this.txid = txid; }
    synchronized boolean getConfirmed() { return confirmed; }
    synchronized void    setConfirmed() { confirmed = true; }
  }
  
  static final HashMap<String, ChallengeProcess> chps = (new HashMap<String, ChallengeProcess>());
  
  static final LinkedBlockingQueue<ChallengeProcess> chpq = (new LinkedBlockingQueue<ChallengeProcess>());
  
  static {
    (new Thread() {
        public void run()
        {
          try {
            final NetworkParameters params = TestNet3Params.get();
            final String kitFilePrefix = "challenge-standalone-testnet";
            final WalletAppKit kit = (new WalletAppKit(params, (new File(".")), kitFilePrefix));
            
            kit.startAsync();
            kit.awaitRunning();
            
            final Wallet wallet = kit.wallet();
            
            System.out.println("current receive address: " + wallet.currentReceiveAddress());
            System.out.println("balance: " + wallet.getBalance().toFriendlyString());
            
            while (true) {
              final ChallengeProcess chp = chpq.take();
              
              // to get coins: https://testnet.manu.backend.hamburg/faucet
              final String faucetReturnAddressString = "2N8hwP1WmJrFF5QWABn38y63uYLhnJYJYTF";
              final Address faucetReturnAddress = Address.fromBase58(params, faucetReturnAddressString);
              SendRequest request = SendRequest.to(faucetReturnAddress, Coin.parseCoin("0.01"));

              {
                String seal = Util.hexdigest(chp.amount + ":" + chp.sender + ":" + chp.recipient + ":" + chp.routingnr + ":" + chp.accountnr + ":" + chp.attachment);
                request.tx.addOutput(Coin.ZERO, ScriptBuilder.createOpReturnScript(seal.getBytes(Util.UTF_8)));
              }
              
              request.feePerKb = Coin.parseCoin("0.05");
              
              wallet.completeTx(request);
              
              wallet.commitTx(request.tx);
              
              Futures.addCallback
                (kit.peerGroup().broadcastTransaction(request.tx).broadcast(),
                 (new FutureCallback<Transaction>()
                  {
                    public void onSuccess(Transaction transaction)
                    {
                      System.err.println("submitted: " + transaction.getHashAsString());
                      chp.setSubmitted(transaction.getHashAsString());
                      
                      Futures.addCallback
                        (transaction.getConfidence().getDepthFuture(1),
                         (new FutureCallback<TransactionConfidence>()
                          {
                            public void onSuccess(TransactionConfidence result)
                            {
                              System.out.println("confirmed: " + transaction.getHashAsString());
                              chp.setConfirmed();
                            }
                            
                            public void onFailure(Throwable t)
                            {
                              System.out.println("nonconfirmed: " + transaction.getHashAsString());
                            }
                           }));
                    }
                    
                    public void onFailure(Throwable t)
                    {
                      System.err.println("nonsubmitted!");
                    }
                   }));
            }
          } catch (Throwable t) {
            System.err.println(t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
          }
        }
      }).start();
  }
  
  public static class ChallengeServlet extends HttpServlet
  {
    public static enum RequestType
    {
      GET,
      POST
    }
    
    private void respond(HttpServletResponse res, String contentType, String content)
    {
      try {
        res.setHeader("Content-Type", contentType);
        res.getOutputStream().write(content.getBytes(Util.UTF_8));
      } catch (IOException e) {
        throw (new RuntimeException(e));
      }
    }
    
    private void handleTokenCreate(HttpServletRequest req, HttpServletResponse res)
    {
      try {
        String amount = null;
        String sender = null;
        String recipient = null;
        String routingnr = null;
        String accountnr = null;
        String attachment = null;
        
        // fix to enable multipart in Jetty; see discussion at
        // https://dev.eclipse.org/mhonarc/lists/jetty-users/msg03294.html
        req.setAttribute
          (org.eclipse.jetty.server.Request.__MULTIPART_CONFIG_ELEMENT,
           (new MultipartConfigElement(System.getProperty("java.io.tmpdir"))));
        
        for (Part part : req.getParts()) {
          /****/ if (part.getName().equals("amount")) {
            amount = Util.slurp(part.getInputStream());
          } else if (part.getName().equals("sender")) {
            sender = Util.slurp(part.getInputStream());
          } else if (part.getName().equals("recipient")) {
            recipient = Util.slurp(part.getInputStream());
          } else if (part.getName().equals("routingnr")) {
            routingnr = Util.slurp(part.getInputStream());
          } else if (part.getName().equals("accountnr")) {
            accountnr = Util.slurp(part.getInputStream());
          } else if (part.getName().equals("attachment")) {
            // in production, files would not normally be loaded into
            // memory like this but rather be streamed through to
            // persistent storage. since this is just a mock up, let's
            // load into memory.
            attachment = Util.slurp(part.getInputStream());
          }
        }
        
        // make sure we got a value for everything. in production,
        // more validation of inputs would be done here.
        if (amount == null) throw null;
        if (sender == null) throw null;
        if (recipient == null) throw null;
        if (routingnr == null) throw null;
        if (accountnr == null) throw null;
        if (attachment == null) throw null;
        
        // let's generate a token
        String token = secureRandomToken();
        
        synchronized (chps) {
          // let's associate a process with the token
          ChallengeProcess chp = (new ChallengeProcess(amount, sender, recipient, routingnr, accountnr, attachment));
          chps.put(token, chp);
          
          // and enqueue it for processing
          chpq.add(chp);
        }
        
        // respond with the token
        respond(res, "text/plain", (token + "\n"));
      } catch (IOException e) {
        throw (new RuntimeException(e));
      } catch (ServletException e) {
        throw (new RuntimeException(e));
      }
    }
    
    private void handleTokenList(HttpServletRequest req, HttpServletResponse res)
    {
      StringBuilder out = (new StringBuilder());
      
      synchronized (chps) {
        for (Map.Entry<String, ChallengeProcess> entry : chps.entrySet()) {
          final String token = entry.getKey();
          final ChallengeProcess chp = entry.getValue();
          
          out.append(token + " ");
          
          if (chp.getSubmitted()) {
            if (chp.getConfirmed()) {
              out.append("confirmed " + chp.getTxid());
            } else {
              out.append("submitted " + chp.getTxid());
            }
          } else {
            out.append("unsubmitted n_a");
          }
          
          out.append("\n");
        }
      }
      
      respond(res, "text/plain", out.toString());
    }
    
    private void handleTokenRetrieve(HttpServletRequest req, HttpServletResponse res, String token)
    {
      respond(res, "text/plain", "not implemented");
    }
    
    private void handle(RequestType rqt, HttpServletRequest req, HttpServletResponse res)
    {
      final String uri = req.getRequestURI();
      final StringTokenizer tok = (new StringTokenizer(uri, "/"));
      
      // read "tokens"
      if (tok.hasMoreTokens()) {
        String tokens = tok.nextToken();
        
        if (!(tokens.equals("tokens"))) throw null;
        
        // was a specific token specified?
        if (tok.hasMoreTokens()) {
          String token = tok.nextToken();
          
          // ok but that should be the end of the input
          if (tok.hasMoreTokens()) throw null;
          
          // expecting a GET request at this point
          if (rqt == RequestType.GET) {
            handleTokenRetrieve(req, res, token);
            return;
          }
        } else {
          // no token specified; we're down to create or list
          // depending on the type of request
          
          if (rqt == RequestType.POST) {
            handleTokenCreate(req, res);
            return;
          }
          
          if (rqt == RequestType.GET) {
            handleTokenList(req, res);
            return;
          }
        }
      }
      
      respond(res, "text/plain", "error: illegal request");
    }
    
    public void doGet(HttpServletRequest req, HttpServletResponse res)
    {
      handle(RequestType.GET, req, res);
    }
    
    public void doPost(HttpServletRequest req, HttpServletResponse res)
    {
      handle(RequestType.POST, req, res);
    }
  }
  
  public static void main(String[] args) throws Exception
  {
    Server server = (new Server(80));
    
    ServletHandler handler = (new ServletHandler());
    handler.addServletWithMapping(ChallengeServlet.class, "/*");
    server.setHandler(handler);
    server.start();
    server.join();
  }
}
