package com.delinea.secrets.jenkins.global.cred;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.delinea.server.spring.Secret;
import com.delinea.server.spring.SecretServer;
import com.delinea.server.spring.SecretServerFactoryBean;

import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;

public class VaultClient {
	private static final Logger LOGGER = Logger.getLogger(VaultClient.class.getName());

	private static final String USERNAME_PROPERTY = "server.username";
	private static final String PASSWORD_PROPERTY = "server.password";
	private static final String SERVER_URL = "server.url";
	private static final String PROXY_HOST_PROPERTY = "proxy.host";
	private static final String PROXY_PORT_PROPERTY = "proxy.port";
	private static final String PROXY_USERNAME_PROPERTY = "proxy.username";
	private static final String PROXY_PASSWORD_PROPERTY = "proxy.password";

	public VaultClient() {
	}

	/**
	 * Fetches credentials from the Secret Server using the provided Vault URL,
	 * secret ID, username, and password.
	 *
	 * @param vaultUrl         The base URL of the Secret server.
	 * @param secretId         The ID of the secret to fetch.
	 * @param username         The username for authenticating with the Vault.
	 * @param password         The password for authenticating with the Vault.
	 * @param usernameSlug
	 * @param passwordSlugName
	 * @param proxyPassword
	 * @param proxyUsername
	 * @param proxyPort
	 * @param proxyHost
	 * @return A UsernamePassword object containing the fetched credentials, or null
	 *         if not found.
	 * @throws Exception if there is an error during the fetching process.
	 */
	public UsernamePassword fetchCredentials(String vaultUrl, String secretId, String username, String password,
			String usernameSlug, String passwordSlugName, String proxyHost, String proxyPort, String proxyUsername,
			String proxyPassword) throws Exception {
		System.err.println(proxyHost + " " + proxyPort + " " + proxyUsername + " " + proxyPassword);
		// Create a map to hold properties for the Secret Server connection
		Map<String, Object> properties = new HashMap<>();

		// Remove trailing slash from the Vault URL if present
		String ssurl = StringUtils.removeEnd(vaultUrl, "/");
		if (StringUtils.isNotBlank(ssurl)) {
			properties.put(SERVER_URL, ssurl);
		}

		properties.put(USERNAME_PROPERTY, username);
		properties.put(PASSWORD_PROPERTY, password);

		String activeProxyHost = proxyHost;
		String activeProxyPort = proxyPort;
		String activeProxyUser = proxyUsername;
		String activeProxyPass = proxyPassword;
		String proxySource;

		// If no Delinea proxy configured, fall back to Jenkins global proxy
		if (StringUtils.isBlank(activeProxyHost) || StringUtils.isBlank(activeProxyPort)) {
			ProxyConfiguration jenkinsProxy = Jenkins.get().proxy;
			if (jenkinsProxy != null && StringUtils.isNotBlank(jenkinsProxy.name)) {
				activeProxyHost = jenkinsProxy.name;
				activeProxyPort = String.valueOf(jenkinsProxy.port);
				activeProxyUser = jenkinsProxy.getUserName();
				activeProxyPass = jenkinsProxy.getPassword();
				proxySource = "Jenkins Global Proxy";
			} else {
				proxySource = "Direct Connection (no proxy configured)";
			}
		} else {
			proxySource = "Delinea Plugin Proxy";
		}

		LOGGER.info(String.format("""
		        [VaultClient] ---------- Proxy Configuration Summary ----------
		            Source         : %s
		            Proxy Host     : %s
		            Proxy Port     : %s
		            Proxy Username : %s
		            Proxy Password : %s
		        ----------------------------------------------------------------
		        """,
		        proxySource,
		        StringUtils.defaultString(activeProxyHost, "(none)"),
		        StringUtils.defaultString(activeProxyPort, "(none)"),
		        StringUtils.isNotBlank(activeProxyUser) ? "*****" : "(none)",
		        StringUtils.isNotBlank(activeProxyPass) ? "********" : "(none)"
		));

		// Add proxy settings to properties if configured
		if (StringUtils.isNotBlank(activeProxyHost) && StringUtils.isNotBlank(activeProxyPort)) {
			properties.put(PROXY_HOST_PROPERTY, activeProxyHost);
			properties.put(PROXY_PORT_PROPERTY, activeProxyPort);
			if (StringUtils.isNotBlank(activeProxyUser)) {
				properties.put(PROXY_USERNAME_PROPERTY, activeProxyUser);
			}
			if (StringUtils.isNotBlank(activeProxyPass)) {
				properties.put(PROXY_PASSWORD_PROPERTY, activeProxyPass);
			}
		}

		// Create and configure the application context with the Secret Server
		try (AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext()) {
			applicationContext.getEnvironment().getPropertySources()
					.addLast(new MapPropertySource("properties", properties));
			applicationContext.registerBean(SecretServerFactoryBean.class);
			applicationContext.refresh();

			SecretServer secretServer = applicationContext.getBean(SecretServer.class);

			try {
				Secret secret = secretServer.getSecret(Integer.parseInt(secretId));

				Optional<String> fetchUsername = secret.getFields().stream()
						.filter(field -> usernameSlug.equalsIgnoreCase(field.getFieldName())
								|| usernameSlug.equalsIgnoreCase(field.getSlug()))
						.map(Secret.Field::getValue).findFirst();

				Optional<String> fetchPassword = secret.getFields().stream()
						.filter(field -> passwordSlugName.equalsIgnoreCase(field.getFieldName())
								|| passwordSlugName.equalsIgnoreCase(field.getSlug()))
						.map(Secret.Field::getValue).findFirst();

				if (fetchUsername.isPresent() && fetchPassword.isPresent()) {
					return new UsernamePassword(fetchUsername.get(), fetchPassword.get());
				}
				return null;

			} catch (org.springframework.web.client.HttpClientErrorException e) {
                int status = e.getStatusCode().value();
                if (status == 407) {
                    LOGGER.warning("[VaultClient] Proxy authentication failed (HTTP 407). " +
                                   "Verify proxy username/password in Delinea or Jenkins proxy settings.");
                } else if (e.getStatusCode().is4xxClientError()) {
                    LOGGER.warning(() -> "[VaultClient] Client error (" + e.getStatusCode() + "): " + e.getStatusText());
                } else if (e.getStatusCode().is5xxServerError()) {
                    LOGGER.warning(() -> "[VaultClient] Server error (" + e.getStatusCode() + "): " + e.getStatusText());
                }
                throw new Exception("Failed to fetch secret due to proxy or connection issue. HTTP Status: "
                        + e.getStatusCode(), e);

            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "[VaultClient] Unexpected error while fetching secret: " + ex.getMessage(), ex);
                throw ex;
            }
		}
	}

	public static class UsernamePassword {
		private final String username;
		private final hudson.util.Secret password;

		public UsernamePassword(String username, String password) {
			this.username = username;
			this.password = hudson.util.Secret.fromString(password);
		}

		public String getPassword() {
			return password.getPlainText();
		}

		public String getUsername() {
			return username;
		}
	}

}
