package com.oxygenxml.rest.plugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.common.net.MediaType;
import com.google.gson.Gson;

import ro.sync.ecss.extensions.api.webapp.WebappMessage;
import ro.sync.ecss.extensions.api.webapp.access.WebappPluginWorkspace;
import ro.sync.ecss.extensions.api.webapp.plugin.FilterURLConnection;
import ro.sync.ecss.extensions.api.webapp.plugin.UserActionRequiredException;
import ro.sync.exml.plugin.urlstreamhandler.CacheableUrlConnection;
import ro.sync.exml.workspace.api.PluginResourceBundle;
import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;
import ro.sync.net.protocol.FolderEntryDescriptor;
import ro.sync.net.protocol.http.HttpExceptionWithDetails;
import ro.sync.basic.util.URLUtil;

/**
 * Wrapper over an URLConnection that reports 401 exceptions as
 * {@link UserActionRequiredException}.
 *
 * @author mihai_coanda, arnold wang implmented OAuth
 */
public class RestURLConnection extends FilterURLConnection implements CacheableUrlConnection {

	/**
	 * Logger for logging.
	 */
	private static final Logger logger = Logger.getLogger(RestURLConnection.class.getName());

	/**
	 * Header set for all requests in order to allow CMS's to prevent CSRF requests.
	 */
	private static final String CSRF_HEADER = "X-Requested-With";
	private static final String AUTH_HEADER = "authorization";

	/**
	 * Credentials store.
	 */
	public static final Cache<String, Map<String, String>> credentialsMap = CacheBuilder.newBuilder()
			.concurrencyLevel(10).maximumSize(10000).build();

	/**
	 * Prefix of the protocol.
	 *
	 * We translate http to rest-http and https to rest-https.
	 */
	public static final String REST_PROTOCOL = "rest";

	/**
	 * The session ID.
	 */
	private String contextId;

	/**
	 * The URL of the connection if different from that of the underlying
	 * connection.
	 */
	private URL urlOverride;

	/**
	 * The base URL of the CMS.
	 */
	private String cmsBase;

	/**
	 * Constructor method for the URLConnection wrapper.
	 *
	 * @param contextId The session ID.
	 *
	 * @param delegate  the wrapped URLConnection.
	 * @throws UserActionRequiredException if something fails.
	 */
	protected RestURLConnection(String contextId, URLConnection delegate) {
		super(delegate);
		this.contextId = contextId;

		cmsBase = RestURLStreamHandler.getServerUrl();
		addHeaders(this, this.contextId);
	}

	@Override
	public void connect() throws IOException {
		try {
			super.connect();
		} catch (IOException e) {
			handleException(e);
		}
	}

	@Override
	public InputStream getInputStream() throws IOException {
		try {
			InputStream dtdStream = getAssetBinaryInputStreamWithDtdCache();
			if (dtdStream != null) {
				return dtdStream;
			}
			return super.getInputStream();
		} catch (IOException e) {
			handleException(e);

			// Unreachable.
			return null;
		}
	}

	private String getDtdEntContentPath(String name) {
		return cmsBase + "GetDtdEntContent?path=" + name;
	}

	private InputStream getAssetBinaryInputStreamWithDtdCache() throws IOException {
		String query = url.getQuery();

		int index = query.lastIndexOf(".");
		String ext = (index > -1 && index < query.length() - 1) ? query.substring(index + 1) : "";

		String path = getFileUrl(url);
		if (path.startsWith("rest://")) {
			path = "/" + path.substring("rest://".length());
		}
		path = Paths.get(path).getFileName().toString();

		boolean isDtd = ext.equalsIgnoreCase("dtd")
				|| ext.equalsIgnoreCase("mod")
				|| ext.equalsIgnoreCase("ent");

		if (isDtd) {
			InputStream strm = DtdCacher.get(this).getCache(path);

			if (strm != null) {
				return strm;
			}

			HttpURLConnection connection = (HttpURLConnection) new URL(getDtdEntContentPath(path))
					.openConnection();
			addHeaders(connection, contextId);

			strm = connection.getInputStream();
			try {
				DtdCacher.get(this).putCache(path, strm);
				return DtdCacher.get(this).getCache(path);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return null;
	}

	public DtdModelWithContent[] getDtdContents(String[] ids) throws MalformedURLException,
			ProtocolException, IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("ids=" + String.join("|", ids));

		// now hit the oauth auth code url, it is get
		URL dtdUrl = new URL(cmsBase + "automated/OxygenApi/GetDtdEntContent?" + sb.toString());
		HttpURLConnection connection = (HttpURLConnection) dtdUrl.openConnection();
		addHeaders(connection, contextId);

		return getResponseObject(connection, DtdModelWithContent[].class);
	}

	public EnvironmentModel getEnvironment() throws MalformedURLException, ProtocolException, IOException {
		URL environmentUrl = new URL(cmsBase + "/automated/OxygenApi/GetEnvironment");
		HttpURLConnection connection = (HttpURLConnection) environmentUrl.openConnection();
		addHeaders(connection, contextId);

		return getResponseObject(connection, EnvironmentModel.class);
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		try {
			return new FilterOutputStream(super.getOutputStream()) {
				@Override
				public void close() throws IOException {
					try {
						super.close();
						// WA-1358: The server overridden the location.
						String actualLocation = RestURLConnection.this.getHeaderField("Location");
						if (actualLocation != null) {
							RestURLConnection.this.urlOverride = new URL(actualLocation);
						}
					} catch (IOException e) {
						handleException(e);
					}
				}
			};
		} catch (IOException e) {
			handleException(e);

			// Unreachable.
			return null;
		}
	}

	/**
	 * Filters the exceptions.
	 *
	 * @param e the exception to filter.
	 *
	 * @throws UserActionRequiredException if the exception message contains a 401
	 *                                     status.
	 *
	 * @throws IOException                 the param exception if it does not
	 *                                     contain a 401 status.
	 */
	private void handleException(IOException e) throws UserActionRequiredException, IOException {
		URL url = this.delegateConnection.getURL();
		String fileUrl = getFileUrl(url);
		logger.debug("Exception thrown when accessing " + fileUrl);
		if (logger.isDebugEnabled()) {
			e.printStackTrace();
		}
		if (e.getMessage().indexOf("401") != -1) {
			// log failed login attempts.
			String userInfo = url.getUserInfo();
			if (userInfo != null && !userInfo.isEmpty()) {
				String user = URLUtil.extractUser(url.toExternalForm());
				if (user != null && !user.trim().isEmpty()) {
					logger.warn("Failed login attempt of user " + user + " for " + URLUtil.getDescription(fileUrl));
				} else {
					logger.warn("Failed login attempt for " + URLUtil.getDescription(fileUrl));
				}
			}
			PluginResourceBundle rb = ((WebappPluginWorkspace) PluginWorkspaceProvider.getPluginWorkspace())
					.getResourceBundle();
			throw new UserActionRequiredException(new WebappMessage(WebappMessage.MESSAGE_TYPE_CUSTOM,
					rb.getMessage(TranslationTags.AUTHENTICATION_REQUIRED),
					// send back the URL for which to authenticate.
					fileUrl, true));
		} else {
			if (delegateConnection instanceof HttpURLConnection) {
				String serverMessage = null;

				if (e instanceof HttpExceptionWithDetails) {
					HttpExceptionWithDetails detailed = (HttpExceptionWithDetails) e;
					if (detailed.getReasonCode() == HttpStatus.SC_NOT_FOUND) {
						PluginResourceBundle rb = ((WebappPluginWorkspace) PluginWorkspaceProvider.getPluginWorkspace())
								.getResourceBundle();
						serverMessage = rb.getMessage(TranslationTags.FILE_NOT_FOUND);
						URL baseURL = detailed.getBaseURL();
						String fileURL = getFilePath(baseURL);
						serverMessage += " " + fileURL;
					}
				}
				if (serverMessage == null) {
					InputStream errorStream = null;
					try {
						errorStream = ((HttpURLConnection) this.delegateConnection).getErrorStream();
						serverMessage = IOUtils.toString(errorStream);
					} catch (Exception ex) {
						Closeables.closeQuietly(errorStream);
					}
				}
				if (shouldDisplayServerMessage(serverMessage)) {
					throw new IOException(serverMessage, e);
				} else {
					logger.debug("Server message too complex to display to the user: " + serverMessage);
				}
			}
			throw e;
		}
	}

	/**
	 * Decide whether to display the message returned by the REST server.
	 *
	 * @param serverMessage The server message.
	 *
	 * @return <code>true</code> if we should display the server message.
	 */
	private boolean shouldDisplayServerMessage(String serverMessage) {
		boolean shouldDisplay = true;

		if (serverMessage == null) {
			shouldDisplay = false;
		} else {
			if (serverMessage.contains("<body") || serverMessage.contains("</body")) {
				shouldDisplay = false;
			}
			if (serverMessage.contains("<?xml")) {
				shouldDisplay = false;
			}
		}
		return shouldDisplay;
	}

	/**
	 * Adds credentials associated with the given user context to this rest url
	 * connection.
	 */
	public static void addHeaders(URLConnection urlConnection, String contextId) {
		try {
			if (accessToken == null || accessToken.getRefreshTokenExpireTime().isBefore(LocalDateTime.now())) {
				String authCode = getAuthorziationCode();
				accessToken = getAccessToken((authCode));
			} else if (accessToken.getAccessTokenExpireTime().isBefore(LocalDateTime.now())
					&& accessToken.getRefreshTokenExpireTime().isAfter(LocalDateTime.now())) {
				accessToken = getRefreshToken();
			}
		} catch (Exception e) {
			logger.warn(e.getMessage() + "\r\n" + e.getStackTrace());
		}

		// PrintStream os = new PrintStream(codeCon.getOutputStream());
		// os.print(content);
		// os.close();

		// This header is set in order to allow CMS's to prevent CSRF attacks.
		// An attacker can create a form to send a post requests to a rest end-point but
		// won't be able to set this header.
		urlConnection.addRequestProperty(CSRF_HEADER, "RC");

		// set bearer token
		try {
			urlConnection.addRequestProperty(AUTH_HEADER,
					"Bearer " + URLEncoder.encode(accessToken.access_token, "UTF-8"));
		} catch (Exception e) {

		}

		if (contextId == null) {
			// The current request did not match any session - no headers to add.
			return;
		}
		Map<String, String> serverHeaders = credentialsMap.getIfPresent(contextId);
		if (serverHeaders != null) {
			// add all headers to the url connection
			Set<String> keySet = serverHeaders.keySet();
			Iterator<String> keysIterator = keySet.iterator();
			while (keysIterator.hasNext()) {
				String header = keysIterator.next();
				String headerValue = serverHeaders.get(header);
				urlConnection.addRequestProperty(header, headerValue);
			}
		}
	}

	private static String getBaseUrl(URL serverUrl) {

		String path = serverUrl.getFile();
		Integer index = path.indexOf("/automated/");
		return path.substring(0, index);
	}

	public static volatile AccessTokenResponse accessToken;

	private static AccessTokenResponse getRefreshToken()
			throws java.net.MalformedURLException, java.io.IOException, java.net.ProtocolException {

		URL serverUrl = new URL(RestURLStreamHandler.getServerUrl());
		String baseUrl = getBaseUrl(serverUrl);

		String tokenPath = baseUrl + "/oauth/token";

		URL tokenUrl = new URL(serverUrl.getProtocol(), serverUrl.getHost(), tokenPath);

		// now hit the oauth auth code url, it is get

		HttpURLConnection tokenCon = (HttpURLConnection) tokenUrl.openConnection();
		tokenCon.setRequestMethod("POST");
		tokenCon.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		tokenCon.setRequestProperty("Accept", "application/json");
		tokenCon.setDoOutput(true);

		StringBuilder sb = new StringBuilder();
		sb.append("grant_type=refresh_token");
		sb.append("&client_id=" + URLEncoder.encode(RestURLStreamHandler.getClientId(), "UTF-8"));
		sb.append("&client_secret=" + URLEncoder.encode(RestURLStreamHandler.getClientSecret(), "UTF-8"));
		sb.append("&refresh_token=" + URLEncoder.encode(accessToken.refresh_token, "UTF-8"));

		tokenCon.getOutputStream().write(sb.toString().getBytes("UTF-8"));

		AccessTokenResponse token = getResponseObject(tokenCon, AccessTokenResponse.class);
		token.issueTime = LocalDateTime.now();
		return token;
	}

	private static AccessTokenResponse getAccessToken(String authorizationCode)
			throws java.net.MalformedURLException, java.io.IOException, java.net.ProtocolException {

		URL serverUrl = new URL(RestURLStreamHandler.getServerUrl());
		String baseUrl = getBaseUrl(serverUrl);

		String tokenPath = baseUrl + "/oauth/token";

		URL tokenUrl = new URL(serverUrl.getProtocol(), serverUrl.getHost(), tokenPath);

		// now hit the oauth auth code url, it is get

		HttpURLConnection tokenCon = (HttpURLConnection) tokenUrl.openConnection();
		tokenCon.setRequestMethod("POST");
		tokenCon.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		tokenCon.setRequestProperty("Accept", "application/json");
		tokenCon.setDoOutput(true);

		StringBuilder sb = new StringBuilder();
		sb.append("grant_type=authorization_code");
		sb.append("&client_id=" + URLEncoder.encode(RestURLStreamHandler.getClientId(), "UTF-8"));
		sb.append("&client_secret=" + URLEncoder.encode(RestURLStreamHandler.getClientSecret(), "UTF-8"));
		sb.append("&code=" + URLEncoder.encode(authorizationCode, "UTF-8"));

		tokenCon.getOutputStream().write(sb.toString().getBytes("UTF-8"));

		AccessTokenResponse token = getResponseObject(tokenCon, AccessTokenResponse.class);
		token.issueTime = LocalDateTime.now();

		return token;

	}

	private static String getAuthorziationCode()
			throws java.net.MalformedURLException, java.io.IOException, java.net.ProtocolException {
		String authCode = "";

		URL serverUrl = new URL(RestURLStreamHandler.getServerUrl());
		String baseUrl = getBaseUrl(serverUrl);

		String oaCodeUrl = baseUrl + "/oauth/auth?response_type=code&client_id=" + RestURLStreamHandler.getClientId()
				+ "&redirect_uri=urn:ietf:wg:oauth:2.0:oob";

		URL authCodeUrl = new URL(serverUrl.getProtocol(), serverUrl.getHost(), oaCodeUrl);

		// now hit the oauth auth code url, it is get
		HttpURLConnection codeCon = (HttpURLConnection) authCodeUrl.openConnection();
		codeCon.setRequestMethod("GET");
		codeCon.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		codeCon.setRequestProperty("Accept", "application/json");
		AuthorizationCodeResp codeResp = getResponseObject(codeCon, AuthorizationCodeResp.class);

		authCode = codeResp.code;

		return authCode;
	}

	private static <T> T getResponseObject(HttpURLConnection codeCon, Class<T> classOfT) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(codeCon.getInputStream()));
		String line = null;
		StringWriter out = new StringWriter(codeCon.getContentLength() > 0 ? codeCon.getContentLength() : 2048);
		while ((line = reader.readLine()) != null) {
			out.append(line);
		}
		String response = out.toString();

		Gson g = new Gson();
		T codeResp = g.fromJson(response, classOfT);
		return codeResp;
	}

	@Override
	public List<FolderEntryDescriptor> listFolder() throws IOException {
		URL listFolderURL = new URL(url.toExternalForm().replaceFirst("/files", "/folders"));
		URLConnection connection;
		connection = listFolderURL.openConnection();
		connection.addRequestProperty("Accept", MediaType.JSON_UTF_8.toString());
		// Adding headers to the folder listing connection.
		addHeaders(connection, this.contextId);
		connection.connect();

		// Read the server response in a buffer in order to be able to print it for
		// debugging purposes.
		byte[] jsonBytes;
		try {
			jsonBytes = readConnectionBytes(connection);
		} catch (HttpExceptionWithDetails e) {
			logger.debug("Failed to read folder listing from REST server :" + e.getMessage());
			if (HttpStatus.SC_UNAUTHORIZED == e.getReasonCode()) {
				PluginResourceBundle rb = ((WebappPluginWorkspace) PluginWorkspaceProvider.getPluginWorkspace())
						.getResourceBundle();
				throw new UserActionRequiredException(new WebappMessage(WebappMessage.MESSAGE_TYPE_CUSTOM,
						rb.getMessage(TranslationTags.AUTHENTICATION_REQUIRED),
						rb.getMessage(TranslationTags.AUTHENTICATION_REQUIRED), true));
			} else if (HttpStatus.SC_NOT_FOUND == e.getReasonCode()) {
				String folderPath = getFilePath(e.getBaseURL());
				throw new FileNotFoundException(folderPath);
			}
			throw e;
		}
		if (logger.isDebugEnabled()) {
			String jsonFilesString = new String(jsonBytes, Charsets.UTF_8);
			logger.debug("Received folder listing from REST server :" + jsonFilesString);
		}

		ObjectMapper mapper = new ObjectMapper();
		JsonNode[] array;
		try {
			array = mapper.readValue(jsonBytes, mapper.getTypeFactory().constructArrayType(JsonNode.class));
		} catch (IOException e) {
			// The original error message is not user-friendly at all - replace it.
			throw new IOException("Invalid server response", e);
		}

		List<FolderEntryDescriptor> files = new ArrayList<FolderEntryDescriptor>();
		for (int i = 0; i < array.length; i++) {
			JsonNode file = array[i];
			JsonNode folderProp = file.get("folder");
			boolean isFolder = folderProp != null && folderProp.asBoolean();
			String encodedFileName = URLUtil.encodeURIComponent(file.get("name").asText());
			String filePath = getDocumenURL() + encodedFileName + (isFolder ? "/" : "");
			logger.debug("Add parsed file path :" + filePath);
			files.add(new FolderEntryDescriptor(filePath));
		}
		return files;
	}

	/**
	 * Read the bytes from the given connection.
	 *
	 * @param connection The URL connection.
	 * @return The bytes read.
	 *
	 * @throws IOException Any exception caught when reading from the URL.
	 */
	private byte[] readConnectionBytes(URLConnection connection) throws IOException {
		byte[] jsonBytes;
		InputStream inputStream = connection.getInputStream();
		try {
			jsonBytes = ByteStreams.toByteArray(inputStream);
		} finally {
			try {
				inputStream.close();
			} catch (IOException e) {
				// Ignore the exception - we already read the server response.
			}
		}
		return jsonBytes;
	}

	/**
	 * @return the document url string from the delegate connection.
	 */
	private String getDocumenURL() {
		String restEndpoint = "/files";
		StringBuilder fullURL = new StringBuilder(url.toExternalForm());
		int endpointIndex = fullURL.indexOf(restEndpoint);
		fullURL.delete(0, endpointIndex + restEndpoint.length());
		int slashIndex = fullURL.indexOf("/");
		// remove additional path sections
		if (slashIndex != -1) {
			fullURL.delete(slashIndex, fullURL.length());
		}
		return URLUtil.decodeURIComponent(URLUtil.decodeURIComponent(fullURL.toString()));
	}

	@Override
	public URL getURL() {
		if (this.urlOverride != null) {
			return this.urlOverride;
		} else {
			URL requestURL = super.getURL();
			String fileUrl = getFileUrl(requestURL);
			try {
				return new URL(fileUrl);
			} catch (MalformedURLException e) {
				return requestURL;
			}
		}
	}

	/**
	 * Return the path of the file referred to by the given request URL.
	 *
	 * @param requestURL The request URL.
	 *
	 * @return The file URL.
	 */
	private static String getFilePath(URL requestURL) {
		String fileUrl = getFileUrl(requestURL);
		String filePath = fileUrl;
		try {
			filePath = new URI(fileUrl).getPath();
		} catch (URISyntaxException se) {
			// use the full URL if it cannot be parsed
		}
		return filePath;
	}

	/**
	 * Return the file URL referred to by the given request URL. It is a query
	 * parameter with name "url".
	 *
	 * @param requestURL The request URL.
	 *
	 * @return The file URL.
	 */
	@VisibleForTesting
	static String getFileUrl(URL requestURL) {
		List<NameValuePair> params = URLEncodedUtils.parse(requestURL.getQuery(), Charsets.UTF_8);
		String encodedFileUrl = null;
		for (NameValuePair pair : params) {
			if (pair.getName().equals("url")) {
				encodedFileUrl = pair.getValue();
			}
		}
		String fileUrl = requestURL.toExternalForm();
		if (encodedFileUrl != null) {
			fileUrl = URLUtil.decodeURIComponent(encodedFileUrl);
		}
		return fileUrl;
	}
}
