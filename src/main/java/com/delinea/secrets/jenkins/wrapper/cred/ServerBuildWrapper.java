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

import com.delinea.secrets.server.spring.Secret;
import com.delinea.secrets.server.spring.SecretServer;
import com.delinea.secrets.server.spring.SecretServerFactoryBean;

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
import jenkins.tasks.SimpleBuildWrapper;

public class ServerBuildWrapper extends SimpleBuildWrapper {
    private static final String USERNAME_PROPERTY = "secret_server.oauth2.username";
    private static final String PASSWORD_PROPERTY = "secret_server.oauth2.password";
    private static final String API_ROOT_URL_PROPERTY = "secret_server.api_root_url";
    private static final String OAUTH2_TOKEN_URL_PROPERTY = "secret_server.oauth2.token_url";
    
 // Proxy property names for SecretServerFactoryBean
    private static final String PROXY_HOST_PROPERTY = "secret_server.proxy.host";
    private static final String PROXY_PORT_PROPERTY = "secret_server.proxy.port";
    private static final String PROXY_USERNAME_PROPERTY = "secret_server.proxy.username";
    private static final String PROXY_PASSWORD_PROPERTY = "secret_server.proxy.password";

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

        // these may be overridden by the secret below
        properties.put(API_ROOT_URL_PROPERTY, configuration.getAPIUrl());
        properties.put(OAUTH2_TOKEN_URL_PROPERTY, configuration.getTokenUrl());
        secrets.forEach(serverSecret -> {
        	 // Print proxy details coming from Jelly form inputs
            listener.getLogger().println("[ServerBuildWrapper] ---------- Proxy Details (from ServerSecret) ----------");
            listener.getLogger().println("    Proxy Host     : " + StringUtils.defaultString(serverSecret.getProxyHost(), "(none)"));
            listener.getLogger().println("    Proxy Port     : " + (serverSecret.getProxyPort() != 0 ? serverSecret.getProxyPort() : "(none)"));
            listener.getLogger().println("    Proxy Username : " + StringUtils.defaultString(serverSecret.getProxyUsername(), "(none)"));
            listener.getLogger().println("    Proxy Password : " + (StringUtils.isNotBlank(serverSecret.getProxyPassword()) ? "********" : "(none)"));
            listener.getLogger().println("----------------------------------------------------------");
            final String overrideBaseURL = serverSecret.getBaseUrl();
            final String overrideUserCredentialId = serverSecret.getCredentialId();

         // Add proxy settings to properties if configured
            if (StringUtils.isNotBlank(serverSecret.getProxyHost()) && serverSecret.getProxyPort() > 0) {
                properties.put(PROXY_HOST_PROPERTY, serverSecret.getProxyHost());
                properties.put(PROXY_PORT_PROPERTY, serverSecret.getProxyPort());

                if (StringUtils.isNotBlank(serverSecret.getProxyUsername())) {
                    properties.put(PROXY_USERNAME_PROPERTY, serverSecret.getProxyUsername());
                }
                if (StringUtils.isNotBlank(serverSecret.getProxyPassword())) {
                    properties.put(PROXY_PASSWORD_PROPERTY, serverSecret.getProxyPassword());
                }
            }
            
            if (StringUtils.isNotBlank(overrideBaseURL)) {
                properties.put(API_ROOT_URL_PROPERTY, overrideBaseURL + configuration.getApiPathUri());
                properties.put(OAUTH2_TOKEN_URL_PROPERTY, overrideBaseURL + configuration.getTokenPathUri());
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
