package com.delinea.secrets.jenkins.util;

import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Utility to resolve Delinea + Jenkins proxy configuration safely and in
 * compliance with access-modifier rules.
 */
public class DelineaProxyUtil {

	private static final Logger LOGGER = Logger.getLogger(DelineaProxyUtil.class.getName());

	public static final String PROXY_HOST_PROPERTY = "proxy.host";
	public static final String PROXY_PORT_PROPERTY = "proxy.port";
	public static final String PROXY_USERNAME_PROPERTY = "proxy.username";
	public static final String PROXY_PASSWORD_PROPERTY = "proxy.password";

	public static Map<String, String> resolveProxy(String vaultUrl, String proxyHost, String proxyPort,
			String proxyUsername, String proxyPassword, String noProxyHosts) {

		String activeProxyHost = proxyHost;
		String activeProxyPort = proxyPort;
		String activeProxyUser = proxyUsername;
		String activeProxyPass = proxyPassword;
		String proxySource;

		String targetHost = extractHost(vaultUrl);

		// Always check if noProxyHosts matches first
		if (StringUtils.isNotBlank(noProxyHosts) && isHostInNoProxy(targetHost, noProxyHosts)) {
			LOGGER.info("[DelineaProxyUtil] Skipping proxy due to NO_PROXY match for: " + targetHost);
			proxySource = "Direct Connection (NO_PROXY match)";
			return logAndReturn(proxySource, null, null, null, null);
		}

		// If plugin proxy not provided, try Jenkins proxy
		if (StringUtils.isBlank(activeProxyHost) || StringUtils.isBlank(activeProxyPort)) {
			LOGGER.info("[DelineaProxyUtil] inside jenkins proxy");
			ProxyConfiguration jenkinsProxy = Jenkins.get().proxy;

			if (jenkinsProxy != null && StringUtils.isNotBlank(jenkinsProxy.name)) {

				// Jenkins NO_PROXY check (environment-based)
				String jenkinsNoProxy = getNoProxyList(jenkinsProxy);
				if (isHostInNoProxy(targetHost, jenkinsNoProxy)) {
					LOGGER.info("[DelineaProxyUtil] Skipping Jenkins proxy due to NO_PROXY match for: " + targetHost);
					proxySource = "Direct Connection (NO_PROXY match)";
					return logAndReturn(proxySource, null, null, null, null);
				}

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

		return logAndReturn(proxySource, activeProxyHost, activeProxyPort, activeProxyUser, activeProxyPass);
	}

	private static String extractHost(String url) {
		if (StringUtils.isBlank(url))
			return "";
		try {
			URI uri = new URI(url);
			return uri.getHost() != null ? uri.getHost() : url;
		} catch (URISyntaxException e) {
			return url;
		}
	}

	/**
	 * Safe retrieval of NO_PROXY list without violating access modifiers.
	 */
	private static String getNoProxyList(ProxyConfiguration proxy) {
	    if (proxy == null) return "";

	    try {
	        // Safely access the noProxyHost field reflectively
	        java.lang.reflect.Field field = ProxyConfiguration.class.getDeclaredField("noProxyHost");
	        field.setAccessible(true);
	        Object value = field.get(proxy);
	        if (value instanceof String && StringUtils.isNotBlank((String) value)) {
	            return (String) value;
	        }
	    } catch (NoSuchFieldException | IllegalAccessException e) {
	        LOGGER.warning("[DelineaProxyUtil] Unable to access noProxyHost field: " + e.getMessage());
	    }

	    // fallback to environment
	    String sysNoProxy = System.getenv("NO_PROXY");
	    if (StringUtils.isNotBlank(sysNoProxy)) {
	        return sysNoProxy;
	    }

	    return System.getProperty("no_proxy", "");
	}

	private static boolean isHostInNoProxy(String host, String noProxyList) {
		if (StringUtils.isBlank(host) || StringUtils.isBlank(noProxyList))
			return false;

		for (String entry : noProxyList.split(",")) {
			entry = entry.trim();
			if (StringUtils.isNotBlank(entry) && (host.equalsIgnoreCase(entry) || host.endsWith(entry))) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, String> logAndReturn(String source, String host, String port, String user, String pass) {
		 String message = String.format(
		            "[DelineaProxyUtil] ---------- Proxy Configuration ----------%n" +
		            "    Source         : %s%n" +
		            "    Proxy Host     : %s%n" +
		            "    Proxy Port     : %s%n" +
		            "    Proxy Username : %s%n" +
		            "    Proxy Password : %s%n" +
		            "-------------------------------------------------------------%n",
		            source,
		            StringUtils.defaultString(host, "(none)"),
		            StringUtils.defaultString(port, "(none)"),
		            StringUtils.isNotBlank(user) ? "*****" : "(none)",
		            StringUtils.isNotBlank(pass) ? "********" : "(none)"
		    );

		    LOGGER.info(message);
		Map<String, String> proxyConfig = new HashMap<>();
		if (StringUtils.isNotBlank(host))
			proxyConfig.put(PROXY_HOST_PROPERTY, host);
		if (StringUtils.isNotBlank(port))
			proxyConfig.put(PROXY_PORT_PROPERTY, port);
		if (StringUtils.isNotBlank(user))
			proxyConfig.put(PROXY_USERNAME_PROPERTY, user);
		if (StringUtils.isNotBlank(pass))
			proxyConfig.put(PROXY_PASSWORD_PROPERTY, pass);
		return proxyConfig;
	}
}
