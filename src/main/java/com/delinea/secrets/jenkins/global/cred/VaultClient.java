package com.delinea.secrets.jenkins.global.cred;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.delinea.platform.service.AuthenticationService;
import com.delinea.secrets.jenkins.util.DelineaProxyUtil;
import com.delinea.server.spring.Secret;
import com.delinea.server.spring.SecretServer;
import com.delinea.server.spring.SecretServerFactoryBean;

public class VaultClient {
	private static final Logger LOGGER = Logger.getLogger(VaultClient.class.getName());

	private static final String USERNAME_PROPERTY = "server.username";
	private static final String PASSWORD_PROPERTY = "server.password";
	private static final String SERVER_URL_PROPERTY = "server.url";

	public VaultClient() {
	}

	public UsernamePassword fetchCredentials(String vaultUrl, String secretId, String username, String password,
			String usernameSlug, String passwordSlugName, String proxyHost, String proxyPort, String proxyUsername,
			String proxyPassword, String noProxyHosts) throws Exception {

		Map<String, Object> properties = new HashMap<>();
		String trimmedUrl = StringUtils.removeEnd(vaultUrl, "/");

		if (StringUtils.isNotBlank(trimmedUrl)) {
			properties.put(SERVER_URL_PROPERTY, trimmedUrl);
		}
		properties.put(USERNAME_PROPERTY, username);
		properties.put(PASSWORD_PROPERTY, password);

		Map<String, String> proxyConfig = DelineaProxyUtil.resolveProxy(trimmedUrl, proxyHost, proxyPort, proxyUsername,
				proxyPassword, noProxyHosts);

		proxyConfig.forEach(properties::put);

		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addLast(new MapPropertySource("properties", properties));
			context.registerBean(SecretServerFactoryBean.class);
			context.register(AuthenticationService.class);
			try {
				context.refresh();
				SecretServer secretServer = context.getBean(SecretServer.class);

				Secret secret = secretServer.getSecret(Integer.parseInt(secretId));
				Optional<String> fetchedUser = secret.getFields().stream()
						.filter(f -> usernameSlug.equalsIgnoreCase(f.getFieldName())
								|| usernameSlug.equalsIgnoreCase(f.getSlug()))
						.map(Secret.Field::getValue).findFirst();

				Optional<String> fetchedPass = secret.getFields().stream()
						.filter(f -> passwordSlugName.equalsIgnoreCase(f.getFieldName())
								|| passwordSlugName.equalsIgnoreCase(f.getSlug()))
						.map(Secret.Field::getValue).findFirst();

				if (fetchedUser.isPresent() && fetchedPass.isPresent()) {
					return new UsernamePassword(fetchedUser.get(), fetchedPass.get());
				}
				LOGGER.warning("[VaultClient] Secret retrieved but missing expected username/password fields.");
				return null;

			}  catch (Exception e) {
			    Throwable root = e;
			    while (root.getCause() != null) {
			        root = root.getCause();
			    }

			    if (root instanceof java.net.UnknownHostException) {
			        LOGGER.severe("[VaultClient] Host not found: " + root.getMessage());
			    } else if (root instanceof org.springframework.web.client.HttpClientErrorException) {
			        int status = ((org.springframework.web.client.HttpClientErrorException) root).getStatusCode().value();
			        if (status == 407) {
			            LOGGER.warning("[VaultClient] Proxy authentication failed (HTTP 407).");
			        } else if (status == 400) {
			            LOGGER.warning("[VaultClient] Access denied / invalid client credentials (HTTP 400).");
			        } else {
			            LOGGER.warning("[VaultClient] HTTP error (status " + status + ").");
			        }
			    } else if (root instanceof java.io.IOException) {
			        LOGGER.severe("[VaultClient] Network I/O error: " + root.getMessage());
			    } else {
			        LOGGER.log(Level.SEVERE, "[VaultClient] Unexpected error: " + e.getMessage(), e);
			    }
			    throw e;
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

		public String getUsername() {
			return username;
		}

		public String getPassword() {
			return password.getPlainText();
		}
	}
}
