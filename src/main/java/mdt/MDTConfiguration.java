package mdt;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import javax.annotation.Nullable;

import org.hibernate.jpa.HibernatePersistenceProvider;
import org.mandas.docker.client.exceptions.DockerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Maps;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import utils.UnitUtils;
import utils.func.FOption;

import mdt.client.HttpServiceFactory;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.instance.docker.DockerConfiguration;
import mdt.instance.docker.DockerInstanceManager;
import mdt.instance.docker.HarborConfiguration;
import mdt.instance.external.ExternalConfiguration;
import mdt.instance.external.ExternalInstanceManager;
import mdt.instance.jar.JarInstanceManager;
import mdt.instance.jpa.InstancePersistenceUnitInfo;
import mdt.instance.k8s.KubernetesInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.workflow.JpaWorkflowModelManager;
import mdt.workflow.MDTWorkflowManagerConfiguration;
import mdt.workflow.OpenApiArgoWorkflowManager;
import mdt.workflow.WorkflowManager;
import mdt.workflow.WorkflowModelManager;

import jakarta.persistence.EntityManagerFactory;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Configuration
public class MDTConfiguration {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTConfiguration.class);
	
	// ServiceFactory
	@Bean
	public HttpServiceFactory getServiceFactory() throws MDTInstanceManagerException {
		try {
			return new HttpServiceFactory();
		}
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}
	
	//
	// MDTInstanceManager
	//

	public class MDTInstanceManagerConfiguration {
		private String m_type;				// "jar", "docker", "kubernetes", "external"
		private File m_homeDir;			// MDTInstanceManager home 디렉토리.
		private File m_bundlesDir;   		// MDTInstanceManager bundle 디렉토리.
		private File m_instancesDir;		// MDTInstanceManager instance 디렉토리.
		private File m_defaultMDTInstanceJarFile;	// MDTInstance 수행을 위한 기본 jar 파일
		private String m_repositoryEndpointFormat;	// MDTInstance 접속을 위한 URL 포맷
		
		public String getType() {
			return m_type;
		}

		public void setType(String type) {
			m_type = type;
		}
		
		public File getHomeDir() {
			return m_homeDir;
		}
		
		public void setHomeDir(File homeDir) {
			m_homeDir = homeDir;
		}
		
		public File getInstancesDir() {
			return m_instancesDir;
		}
		
		public void setInstancesDir(File instancesDir) {
			m_instancesDir = instancesDir;
		}
		
		public File getBundlesDir() {
			return m_bundlesDir;
		}
		
		public void setBundlesDir(File bundlesDir) {
			m_bundlesDir = bundlesDir;
		}
		
		public File getDefaultMDTInstanceJarFile() {
			return m_defaultMDTInstanceJarFile;
		}
		
		public void setDefaultMDTInstanceJarFile(File jarFile) {
			m_defaultMDTInstanceJarFile = jarFile;
		}
		
		public String getRepositoryEndpointFormat() {
			return m_repositoryEndpointFormat;
		}
		
		public void setRepositoryEndpointFormat(String format) {
			m_repositoryEndpointFormat = format;
		}
	}
	@Bean
	@ConfigurationProperties(prefix = "instance-manager")
	public MDTInstanceManagerConfiguration getMDTInstanceManagerConfiguration() {
		return new MDTInstanceManagerConfiguration();
	}

	@Bean
	AbstractJpaInstanceManager<? extends JpaInstance> getMDTInstanceManager()
		throws DockerException, InterruptedException {
		MDTInstanceManagerConfiguration conf = getMDTInstanceManagerConfiguration();
		AbstractJpaInstanceManager<? extends JpaInstance> instManager = switch ( conf.getType() ) {
			case "jar" -> new JarInstanceManager(this);
			case "docker" -> new DockerInstanceManager(this);
			case "kubernetes" -> new KubernetesInstanceManager(this);
			case "external" -> new ExternalInstanceManager(this);
			default -> throw new MDTInstanceManagerException("Unknown MDTInstanceManager type: " + conf.getType());
		};
		
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("use MDTInstanceManager: {}", instManager);
		}
		
		return instManager;
	}
	
//	@Bean
//	WorkflowManager getWorkflowManager() {
//		return new OpenApiArgoWorkflowManager();
//	}
//	
//	@Bean
//	WorkflowModelManager getWorkflowModelManager() {
//		return new JpaWorkflowModelManager();
//	}

	@Bean
	@ConfigurationProperties(prefix = "executor")
	public JarExecutorConfiguration getJarExecutorConfiguration() {
		return new JarExecutorConfiguration();
	}
	@Data
	public static class JarExecutorConfiguration {
		private File workspaceDir;
		private Duration sampleInterval;
		private Duration startTimeout;
		private int startConcurrency = 7;
		private File defaultMDTInstanceJarFile;
		private String heapSize;
	}

	@Bean
	@ConfigurationProperties(prefix = "external")
	public ExternalConfiguration getExternalConfiguration() {
		return new ExternalConfiguration();
	}

	@Bean
	@ConfigurationProperties(prefix = "docker")
	public DockerConfiguration getDockerConfiguration() {
		return new DockerConfiguration();
	}

	@Bean
	@ConfigurationProperties(prefix = "harbor")
	public HarborConfiguration getHarborConfiguration() {
		return new HarborConfiguration();
	}
	
	@Bean
	@ConfigurationProperties(prefix = "mqtt")
	public MqttConfiguration getMqttConfiguration() {
		return new MqttConfiguration();
	}
	@Data
	public static class MqttConfiguration {
		@Nullable private String clientId = null;
		private String endpoint;
		private int qos = 0;
		private Duration reconnectInterval = Duration.ofSeconds(5);
		
		@JsonProperty("reconnectInterval")
		public void setReconnectRetryIntervalForJackson(String durStr) {
			reconnectInterval = FOption.map(durStr, UnitUtils::parseDuration);
		}
	}
	
	
	
	@Bean
	public EntityManagerFactory getEntityManagerFactory() {
		JpaConfiguration jpaConf = getJpaConfiguration();
		
		if ( s_logger.isInfoEnabled() ) {
			JdbcConfiguration jdbcConf = jpaConf.getJdbc();
			s_logger.info("JDBCConfiguration: url={}, user={}, password={}",
							jdbcConf.getUrl(), jdbcConf.getUser(), jdbcConf.getPassword());
			
			Map<String,String> jpaProps = jpaConf.getProperties();
			s_logger.info("JPA Properties: {}", jpaProps);
		}
		
//		return Persistence.createEntityManagerFactory("MDTInstanceManager");
		InstancePersistenceUnitInfo pUnitInfo = new InstancePersistenceUnitInfo(jpaConf);
		return new HibernatePersistenceProvider()
					.createContainerEntityManagerFactory(pUnitInfo, Maps.newHashMap());
	}
	
	@Bean
    @ConfigurationProperties(prefix = "jpa")
	public JpaConfiguration getJpaConfiguration() {
		return new JpaConfiguration();
	}
	
	@Data
	public static class JpaConfiguration {
		private JdbcConfiguration jdbc;
		private Map<String,String> properties;
	}
	
	@Getter @Setter
	public static class JdbcConfiguration {
		private String url;
		private String user;
		private String password;
		
		public String toString() {
			return String.format("url=%s, user=%s", this.url, this.user);
		}
	}
	
	@Autowired private MDTWorkflowManagerConfiguration m_workflowConf;
	@Bean
	WorkflowManager getWorkflowManager() {
		WorkflowModelManager modelMgr = new JpaWorkflowModelManager(getEntityManagerFactory());
		return new OpenApiArgoWorkflowManager(modelMgr, m_workflowConf);
	}
}
