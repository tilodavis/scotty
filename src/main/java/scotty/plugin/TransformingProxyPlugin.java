package scotty.plugin;

import java.io.IOException;
import java.util.logging.Logger;

import org.owasp.webscarab.httpclient.HTTPClient;
import org.owasp.webscarab.model.HttpUrl;
import org.owasp.webscarab.model.Request;
import org.owasp.webscarab.model.Response;
import org.owasp.webscarab.plugin.proxy.ProxyPlugin;

import scotty.Scotty;
import scotty.transformer.RequestTransformer;
import scotty.transformer.ResponseTransformer;
import scotty.util.UserAgentProvider;

/**
 * This plugin intercepts the request/response and does the transformation,
 * specified by {@link RequestTransformer} and {@link ResponseTransformer}.
 * 
 * @author flo
 * 
 */
public class TransformingProxyPlugin extends ProxyPlugin {

	private Logger log = Logger.getLogger(getPluginName());

	private RequestTransformer requestTransformer;

	private ResponseTransformer responseTransformer;

	private UserAgentProvider uaProvider = new UserAgentProvider();

	public TransformingProxyPlugin(RequestTransformer requestTransformer, ResponseTransformer responseTransformer) {
		this.requestTransformer = requestTransformer;
		this.responseTransformer = responseTransformer;
	}
	
	@Override
	public String getPluginName() {
		return "TransformingProxyPlugin";
	}

	public HTTPClient getProxyPlugin(HTTPClient in) {
		return new Plugin(in);
	}

	private class Plugin implements HTTPClient {

		private HTTPClient in;

		public Plugin(HTTPClient in) {
			this.in = in;
		}

		@Override
		public Response fetchResponse(Request request) throws IOException {
			Response response = null;

			byte[] cryptedRequest = requestTransformer
					.transformRequest(request);

			if (Scotty.useGateway) {
				// Build request, which will be sent to the gateway:
				HttpUrl url = request.getURL();
				request = new Request();
				request.setContent(cryptedRequest);

				HttpUrl gateway = new HttpUrl(Scotty.gatewayUrl);
				request.setHeader("Host", gateway.getHost());
				request.setHeader("Accept-Charset",
						"ISO-8859-1,utf-8;q=0.7,*;q=0.3");
				request.setHeader("Accept-Encoding", "deflate");
				request.setHeader("Accept-Language",
						"de-DE,de;q=0.8,en-US;q=0.6,en;q=0.4");
				request.setHeader("Content-Length",
						Integer.toString(cryptedRequest.length));
				request.setHeader("User-Agent", uaProvider.getUserAgent());

				request.setMethod("POST");

				if ("https".equalsIgnoreCase(url.getScheme())) {
					request.setURL(new HttpUrl(Scotty.gatewayUrl + "?ssl=true"));
				} else {
					request.setURL(gateway);
				}

			}

			Response cryptedResponse = in.fetchResponse(request);

			if (Scotty.useGateway) {
				response = responseTransformer
						.transformResponse(cryptedResponse.getContent());
				response.setRequest(request);
			} else {
				response = cryptedResponse;
			}

			return response;
		}
	}
}
