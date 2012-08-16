package scotty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.PublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;

import scotty.crypto.AESEncryption;
import scotty.crypto.CryptoException;
import scotty.crypto.KeyManager;
import scotty.crypto.RSAEncryption;
import scotty.fetcher.Fetcher;
import scotty.fetcher.GoogleFetcher;



public class GatewayServlet extends HttpServlet {

	private KeyManager km = new KeyManager();

	private Map<String, Token> keyCache = new ConcurrentHashMap<String, GatewayServlet.Token>();

	private Fetcher fetcher = new GoogleFetcher();



	public GatewayServlet() {
		try {
			km.readPublicKey("resources:gw-defaultpublickey");
			km.readPrivateKey("resources:gw-defaultprivatekey", null);
			km.readClientPublicKey("resources:clients");

		} catch (CryptoException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}



	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		System.out.println("in doPost()");

		clearCache();
		Message m = getContent(req);

		try {
			String aesPassword = getAesPassword(m);

			byte[] encMessage = Base64.decodeBase64(m.getEncryptedMessage());
			byte[] decodedMessage = AESEncryption.decrypt(encMessage, aesPassword);

			// parse request, execute request..
			byte[] response = fetcher.fetch(decodedMessage);

			byte[] cryptedResponse = AESEncryption.encrypt(response, aesPassword);

			resp.getOutputStream().write(cryptedResponse);

		} catch (CryptoException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			resp.flushBuffer();
		}

	}



	private void clearCache() {
		for (Map.Entry<String, Token> entry : keyCache.entrySet()) {
			long now = getNowTimestamp();

			long age = now - entry.getValue().timestamp;

			if (age > 90 * 60 * 1000) {
				keyCache.remove(entry.getKey());
			}
		}

	}



	public Message getContent(HttpServletRequest req) throws IOException {

		BufferedReader r = new BufferedReader(new InputStreamReader(req.getInputStream()));
		StringBuilder b = new StringBuilder();
		String line;
		while ((line = r.readLine()) != null) {
			b.append(line);
		}

		String c = b.toString();
		String[] contents = c.split("\\|");

		Message m = new Message();
		m.setCryptedToken(contents[0]);
		m.setSignature(Base64.decodeBase64(contents[1]));
		m.setEncryptedMessage(contents[2]);

		return m;
	}



	public String getAesPassword(Message m) throws CryptoException {
		// Cache pruefen.
		Token cachedToken = keyCache.get(m.getCryptedToken());
		if (cachedToken != null) {
			return cachedToken.aesPassword;
		}

		byte[] cryptedToken = Base64.decodeBase64(m.getCryptedToken());
		// token mit privkey entschluesseln.
		byte[] decryptedToken = RSAEncryption.decrypt(cryptedToken, km.getPrivateKey());
		String tokenAndTimestamp = new String(decryptedToken);

		String[] tokenAndTimestampArr = tokenAndTimestamp.split("\\|");

		String aesPassword = tokenAndTimestampArr[0];
		long timestamp = Long.valueOf(tokenAndTimestampArr[1]) * 1000;

		// token timestamp pruefen
		long nowTimestamp = getNowTimestamp();

		long age = nowTimestamp - timestamp;

		if (age <= 90 * 60 * 1000) {
			checkSignature(decryptedToken, m);
		} else {
			throw new CryptoException("Timestamp expired");
		}

		Token t = new Token();
		t.aesPassword = aesPassword;
		t.timestamp = timestamp;
		addToCache(m, t);

		return aesPassword;
	}



	public long getNowTimestamp() {
		Date now = new Date();
		return now.getTime();
	}



	public void addToCache(Message m, Token t) {
		String cryptedToken = m.getCryptedToken();

		keyCache.put(cryptedToken, t);
	}



	/**
	 * TODO Alert if signature is not valid.
	 * 
	 * @param decryptedToken
	 * @param m
	 * @throws CryptoException
	 */
	public void checkSignature(byte[] decryptedToken, Message m) throws CryptoException {
		List<PublicKey> keys = km.getClientPublicKeys();
		boolean valid = false;

		for (PublicKey publicKey : keys) {
			valid = RSAEncryption.verifySign(decryptedToken, m.getSignature(), publicKey);
			if (valid) {
				break;
			}
		}

		if (!valid) {
			throw new CryptoException("Signature is not valid");
		}

	}

	class Token {
		String aesPassword;
		long timestamp;
	}
}