package com.delinea.secrets.jenkins.global.cred;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.verb.POST;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.delinea.secrets.jenkins.global.cred.VaultClient.UsernamePassword;
import com.delinea.secrets.jenkins.wrapper.cred.UserCredentials;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;

public class SecretServerCredentials extends UsernamePasswordCredentialsImpl implements StandardCredentials {

	private static final long serialVersionUID = 1L;
	private final List<Mapping> mappings;
//	private final String mappingUserSlugName;
//	private final String mappingPasswordSlugName;
	private final String vaultUrl;
	private final String credentialId;
	private final String secretId;
	private transient UsernamePassword vaultCredential;

	/**
	 * Constructor to initialize the SecretServerCredentials object.
	 *
	 * @param scope         - The scope of the credentials (GLOBAL, SYSTEM, etc.).
	 * @param id            - The unique ID for the credentials.
	 * @param description   - A description for the credentials.
	 * @param vaultUrl      - The URL of the Secret Server.
	 * @param credentialId- The ID of the credentials stored in Jenkins.
	 * @param secretId      - The ID of the secret stored in the Secret Server.
	 */
	@DataBoundConstructor
	public SecretServerCredentials(final CredentialsScope scope, final String id, final String description, String vaultUrl,
			String credentialId, String secretId, List<Mapping> mappings) {
		super(scope, id, description, null, null);
		this.mappings = mappings;
//		this.mappingUserSlugName = mappingUserSlugName;
//		this.mappingPasswordSlugName = mappingPasswordSlugName;
		this.vaultUrl = vaultUrl;
		this.credentialId = credentialId;
		this.secretId = secretId;
		this.vaultCredential = null;
	}
	
	 public static class Mapping extends AbstractDescribableImpl<Mapping> {
	        private String username;
			private final String mappingUserSlugName;

	        public String getUsername() {
	        	return username;
	        }

	        public void setUsername(String username) {
	            this.username = username;
	        }
	        
	        public String getmappingUserSlugName() {
	            return mappingUserSlugName;
	        }

	        @DataBoundConstructor
	        public Mapping(final String username, final String mappingUserSlugName) {
	            this.username = username;
	            this.mappingUserSlugName = mappingUserSlugName;
	        }
	        
	        @Extension
	        public static final class DescriptorImpl extends Descriptor<Mapping> {
//	            private static final String NAME_PATTERN = "[a-zA-Z_-][a-zA-Z0-9 ]*";

	            @Override
	            public String getDisplayName() {
	                return "Secret Field to Variable Mapping";
	            }

//	            private FormValidation checkPattern(final String value, final String name) {
//	                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
//	                    return FormValidation.error("You do not have permission to perform this action");
//	                }
//	                if (Pattern.matches(NAME_PATTERN, value))
//	                    return FormValidation.ok();
//	                return FormValidation.error(String.format("%s must match %s", name, NAME_PATTERN));
//
//	            }
//
//	            @POST
//	            public FormValidation doCheckEnvironmentVariable(@QueryParameter final String value)
//	                    throws IOException, ServletException {
//	                return checkPattern(value, "Environment Variable");
//	            }
//
//	            @POST
//	            public FormValidation doCheckField(@QueryParameter final String value)
//	                    throws IOException, ServletException {
//	                return checkPattern(value, "Field Name");
//	            }
	        }
	 }

	public String getVaultUrl() {
		return vaultUrl;
	}

	public String getCredentialId() {
		return credentialId;
	}

	public String getSecretId() {
		return secretId;
	}
	
	public List<Mapping> getMappings() {
        return mappings;
    }
	
//	public String getMappingUserSlugName() {
//		return mappingUserSlugName;
//	}
//	
//	public String getMappingPasswordSlugName() {
//		return mappingPasswordSlugName;
//	}

	/**
	 * Fetches the username from the Secret Server.
	 *
	 * @return The username fetched from the Secret Server.
	 */
	@Override
	public String getUsername() {
		return getVaultCredential(getContextItem()).getUsername();
	}

	/**
	 * Fetches the password from the Secret Server.
	 *
	 * @return The password fetched from the Secret Server, wrapped in a Secret
	 *         object.
	 */
//	@Override
//	public Secret getPassword() {
//		return Secret.fromString(getVaultCredential(getContextItem()).getPassword());
//	}

	@Nullable
    private Item getContextItem() {
        // Retrieve the nearest item in the current request context
        if (Stapler.getCurrentRequest() != null) {
            Item contextItem = Stapler.getCurrentRequest().findAncestorObject(Item.class);
            if (contextItem != null) {
                return contextItem;
            }
        }
        return null;
    }

	/**
	 * Fetches the credentials (username and password) from the Secret Server only
	 * once and caches it.
	 *
	 * @return The UsernamePassword object containing the fetched credentials.
	 * @throws RuntimeException if the credentials cannot be fetched from the Secret
	 *                          Server.
	 */
	private UsernamePassword getVaultCredential(@Nullable Item contextItem) {
		if (vaultCredential == null) {
			try {
				UserCredentials credential = UserCredentials.get(credentialId, contextItem);
				if (credential == null) {
					throw new RuntimeException(
							"UserCredentials with the specified credentialId not found in the folder context.");
				}
				vaultCredential = new VaultClient().fetchCredentials(vaultUrl, secretId, credential.getUsername(),
						credential.getPassword().getPlainText(),getMappings());
			} catch (Exception e) {
				throw new RuntimeException("Failed to fetch credentials from vault. " + e.getMessage());
			}
		}
		return vaultCredential;
	}
	
	

	@Extension
	@Symbol("secretServerSecret")
	public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {

		@Override
		public String getDisplayName() {
			return "Secret Server Vault Credentials";
		}

		/**
		 * Populates the list of available Credential IDs for the dropdown in the
		 * Jenkins UI.
		 *
		 * @param owner - The Jenkins item context.
		 * @return A ListBoxModel containing the available Credential IDs.
		 */
		@POST
		public ListBoxModel doFillCredentialIdItems(@AncestorInPath final Item owner) {
		    if ((owner == null && !Jenkins.get().hasPermission(CredentialsProvider.CREATE))
		            || (owner != null && !owner.hasPermission(CredentialsProvider.CREATE))) {
		        return new StandardListBoxModel();
		    }
		    return new StandardListBoxModel()
		            .includeEmptyValue() 
		            .includeAs(ACL.SYSTEM, owner, UserCredentials.class); 
		}

		/**
		 * Validates the Credential ID input by the user.
		 */
		@POST
		public FormValidation doCheckCredentialId(@AncestorInPath Item item, @QueryParameter final String value)
				throws IOException, ServletException {
			if ((item == null && !Jenkins.get().hasPermission(CredentialsProvider.CREATE))
		            || (item != null && !item.hasPermission(CredentialsProvider.CREATE))) {
		        return FormValidation.error("You do not have permission to perform this action.");
		    }
			if (StringUtils.isBlank(value)) {
				return FormValidation.error("Credential ID is required.");
			}
			// Check if the Credential ID exists within the specified item context
			if (CredentialsProvider.lookupCredentials(UserCredentials.class, item, ACL.SYSTEM, Collections.emptyList())
					.stream().noneMatch(cred -> cred.getId().equals(value))) {
				return FormValidation.error("Credential ID not found. Please provide a valid ID.");
			}
			return FormValidation.ok();
		}

		/**
		 * Validates the Secret ID input by the user.
		 */
		@POST
		public FormValidation doCheckSecretId(@AncestorInPath final Item item, @QueryParameter final String value)
				throws IOException, ServletException {
			if ((item == null && !Jenkins.get().hasPermission(CredentialsProvider.CREATE))
		            || (item != null && !item.hasPermission(CredentialsProvider.CREATE))) {
		        return FormValidation.error("You do not have permission to perform this action.");
		    }
			if (StringUtils.isBlank(value)) {
				return FormValidation.error("Secret ID is required.");
			}
			try {
				Integer.parseInt(value);
			} catch (NumberFormatException e) {
				return FormValidation.error("ID must be an integer.");
			}
			return FormValidation.ok();
		}

		/**
		 * Tests the connection to the Secret Server using the provided parameters.
		 *
		 * @param owner        - The Jenkins item context.
		 * @param vaultUrl     - The URL of the Secret Server.
		 * @param credentialId - The ID of the credentials stored in Jenkins.
		 * @param secretId     - The ID of the secret stored in theSecret Server.
		 * @return FormValidation indicating whether the connection was successful or
		 *         not.
		 */
		@POST
		public FormValidation doTestConnection(@AncestorInPath Item owner,
//				@QueryParameter("mappingUserSlugName") final String mappingUserSlugName,
//				@QueryParameter("mappingPasswordSlugName") final String mappingPasswordSlugName,
				@QueryParameter("vaultUrl") final String vaultUrl,
				@QueryParameter("credentialId") final String credentialId,
				@QueryParameter("mappings") final List<Mapping> mappings,
				@QueryParameter("secretId") final String secretId) {
			if ((owner == null && !Jenkins.get().hasPermission(CredentialsProvider.CREATE))
		            || (owner != null && !owner.hasPermission(CredentialsProvider.CREATE))) {
		        return FormValidation.error("You do not have permission to perform this action.");
		    }

			if (StringUtils.isBlank(credentialId)) {
				return FormValidation.error("Credential ID is required to test the connection.");
			}

			if (StringUtils.isBlank(vaultUrl)) {
				return FormValidation.error("Vault URL cannot be blank.");
			}
			
//			if (StringUtils.isBlank(mappingUserSlugName)) {
//				return FormValidation.error("mappingUserSlugName cannot be blank.");
//			}
//			
//			if (StringUtils.isBlank(mappingPasswordSlugName)) {
//				return FormValidation.error("mappingPasswordSlugName cannot be blank.");
//			}
			
			try {
				UserCredentials credential = UserCredentials.get(credentialId, owner);
				new VaultClient().fetchCredentials(vaultUrl, secretId, credential.getUsername(),
						credential.getPassword().getPlainText(), mappings);
				return FormValidation.ok("Connection successful.");
			} catch (Exception e) {
				return FormValidation.error("Failed to establish connection: " + e.getMessage());
			}
		}
	}
}
