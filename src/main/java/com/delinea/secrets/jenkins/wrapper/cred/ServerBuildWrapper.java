package com.delinea.secrets.jenkins.wrapper.cred;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.delinea.server.spring.Secret;
import com.delinea.server.spring.SecretServer;
import com.delinea.server.spring.SecretServerFactoryBean;

import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildWrapper;

public class ServerBuildWrapper extends SimpleBuildWrapper {
    private static final String USERNAME_PROPERTY = "server.username";
    private static final String PASSWORD_PROPERTY = "server.password";
    private static final String SERVER_URL = "server.url";
    private static final String PROXY_HOST_PROPERTY = "proxy.host";
    private static final String PROXY_PORT_PROPERTY = "proxy.port";
    private static final String PROXY_USERNAME_PROPERTY = "proxy.username";
    private static final String PROXY_PASSWORD_PROPERTY = "proxy.password";

    private List<ServerSecret> secrets;
    private List<String> valuesToMask = new ArrayList<>();

    @DataBoundConstructor
    public ServerBuildWrapper(final List<ServerSecret> secrets) {
        this.secrets = secrets;
    }

    public List<ServerSecret> getSecrets() {
        return secrets;
    }

    @DataBoundSetter
    public void setSecrets(final List<ServerSecret> secrets) {
        this.secrets = secrets;
    }

    @Override
    public ConsoleLogFilter createLoggerDecorator(final Run<?, ?> build) {
    	return new ServerConsoleLogFilter(build.getCharset().name(), valuesToMask);
    }
    
    @Override
    public void setUp(final Context context, final Run<?, ?> build, final FilePath workspace, final Launcher launcher,
            final TaskListener listener, final EnvVars initialEnvironment) throws IOException, InterruptedException {
        final ServerConfiguration configuration = ExtensionList.lookupSingleton(ServerConfiguration.class);
        final Map<String, Object> properties = new HashMap<>();

        properties.put(SERVER_URL, configuration.getBaseUrl());
      
        String proxyHost = configuration.getProxyHost();
        int proxyPort = configuration.getProxyPort();
        String proxyUsername = configuration.getProxyUsername();
        String proxyPassword = configuration.getProxyPassword();

        // Fallback to Jenkins global proxy if Delinea proxy not set
        if (StringUtils.isBlank(proxyHost) || proxyPort <= 0) {
            hudson.ProxyConfiguration jenkinsProxy = Jenkins.get().proxy;
            if (jenkinsProxy != null && StringUtils.isNotBlank(jenkinsProxy.name)) {
                listener.getLogger().println("[ServerBuildWrapper] No Delinea proxy configured — using Jenkins global proxy.");
                proxyHost = jenkinsProxy.name;
                proxyPort = jenkinsProxy.port;
                proxyUsername = jenkinsProxy.getUserName();
                proxyPassword = jenkinsProxy.getPassword();
            } else {
                listener.getLogger().println("[ServerBuildWrapper] No proxy configuration found (Delinea or Jenkins). Connecting directly.");
            }
        } else {
            listener.getLogger().println("[ServerBuildWrapper] Using Delinea-specific proxy configuration.");
        }
        
        listener.getLogger().println("[ServerBuildWrapper] ---------- Proxy Configuration Summary ----------");
        listener.getLogger().println(String.format("    Source         : %s",
                StringUtils.isNotBlank(configuration.getProxyHost()) ? "Delinea Plugin Proxy" : "Jenkins Global Proxy "));
        listener.getLogger().println("    Proxy Host     : " + StringUtils.defaultString(proxyHost, "(none)"));
        listener.getLogger().println("    Proxy Port     : " + (proxyPort > 0 ? proxyPort : "(none)"));
        listener.getLogger().println("    Proxy Username : " + (StringUtils.isNotBlank(proxyUsername) ? "*****": "(none)"));
        listener.getLogger().println("    Proxy Password : " + (StringUtils.isNotBlank(proxyPassword) ? "********" : "(none)"));
        listener.getLogger().println("---------------------------------------------------------------------");

        // Add proxy settings to properties if configured
        if (StringUtils.isNotBlank(proxyHost) && proxyPort > 0) {
            properties.put(PROXY_HOST_PROPERTY, proxyHost);
            properties.put(PROXY_PORT_PROPERTY, proxyPort);

            if (StringUtils.isNotBlank(proxyUsername)) {
                properties.put(PROXY_USERNAME_PROPERTY, proxyUsername);
            }
            if (StringUtils.isNotBlank(proxyPassword)) {
                properties.put(PROXY_PASSWORD_PROPERTY, proxyPassword);
            }
        }
        
        secrets.forEach(serverSecret -> {
        	final String overrideBaseURL = serverSecret.getBaseUrl();
            final String overrideUserCredentialId = serverSecret.getCredentialId();
         
            if (StringUtils.isNotBlank(overrideBaseURL)) {
                properties.put(SERVER_URL, overrideBaseURL);
            }

            final UserCredentials credential;

            if (StringUtils.isNotBlank(overrideUserCredentialId)) {
                credential = UserCredentials.get(overrideUserCredentialId, build.getParent());
            } else {
                credential = UserCredentials.get(configuration.getCredentialId(), build.getParent());
            }
            assert (credential != null); // see ServerSecret.DescriptorImpl.doCheckCredentialId

            properties.put(USERNAME_PROPERTY, credential.getUsername());
            properties.put(PASSWORD_PROPERTY, credential.getPassword().getPlainText());

            final AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
            // create a new Spring ApplicationContext using a Map as the PropertySource
            applicationContext.getEnvironment().getPropertySources()
                    .addLast(new MapPropertySource("properties", properties));
            // Register the factoryBean from secrets-java-sdk
            applicationContext.registerBean(SecretServerFactoryBean.class);
            applicationContext.refresh();
            // Fetch the secret
            final Secret secret = applicationContext.getBean(SecretServer.class).getSecret(serverSecret.getId());
            // Add each Secret Field Value with a corresponding mapping to the environment
            secret.getFields().forEach(field -> {
                serverSecret.getMappings().forEach(mapping -> {
                    if (mapping.getField().equalsIgnoreCase(field.getFieldName()) || mapping.getField().equalsIgnoreCase(field.getSlug())) {
                        // Prepend the the environment variable prefix
                        context.env(StringUtils.trimToEmpty(configuration.getEnvironmentVariablePrefix())
                                + mapping.getEnvironmentVariable(), field.getValue());
                        valuesToMask.add(field.getValue());
                    }
                });
            });
            applicationContext.close();
        });
    }

    @Extension
    @Symbol("withSecretServer")
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        @Override
        public boolean isApplicable(final AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Use Delinea Secret Server Secrets";
        }
    }
}
