package mdt;

import java.util.Map;

import org.hibernate.jpa.HibernatePersistenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.common.collect.Maps;

import utils.jdbc.JdbcConfiguration;

import mdt.client.HttpServiceFactory;
import mdt.exector.jar.JarExecutorConfiguration;
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

import jakarta.persistence.EntityManagerFactory;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Configuration
public class MDTConfigurations {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTConfigurations.class);
	
	private static MDTConfigurations s_configs = new MDTConfigurations();
	
	public static MDTConfigurations get() {
		return s_configs;
	}
	
	@Bean
	public HttpServiceFactory getServiceFactory() throws MDTInstanceManagerException {
		try {
			return new HttpServiceFactory();
		}
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}
	
	@Bean
	@ConfigurationProperties(prefix = "instance-manager")
	public MDTInstanceManagerConfiguration getInstanceManagerConfig() {
		return new MDTInstanceManagerConfiguration();
	}
	
	@Bean
	@ConfigurationProperties(prefix = "jpa")
	public JpaConfiguration getJpaConfig() {
		return new JpaConfiguration();
	}
	
	@Bean
	@ConfigurationProperties(prefix = "executor")
	public JarExecutorConfiguration getJarExecutorConfig() {
		return new JarExecutorConfiguration();
	}
	
	@Bean
	@ConfigurationProperties(prefix = "external")
	public ExternalConfiguration getExternalConfig() {
		return new ExternalConfiguration();
	}
	
	@Bean
	@ConfigurationProperties(prefix = "docker")
	public DockerConfiguration getDockerConfig() {
		return new DockerConfiguration();
	}
	
	@Bean
	@ConfigurationProperties(prefix = "harbor")
	public HarborConfiguration getHarborConfig() {
		return new HarborConfiguration();
	}
	
	@Bean
	@ConfigurationProperties(prefix = "mqtt")
	public MqttConfiguration getMqttConfig() {
		return new MqttConfiguration();
	}

	@Bean
	AbstractJpaInstanceManager<? extends JpaInstance> getMDTInstanceManager() throws Exception {
		MDTInstanceManagerConfiguration mgrConfig = getInstanceManagerConfig();
		AbstractJpaInstanceManager<? extends JpaInstance> instManager = switch ( mgrConfig.getType() ) {
			case "jar" -> new JarInstanceManager(this);
			case "docker" -> new DockerInstanceManager(this);
			case "kubernetes" -> new KubernetesInstanceManager(this);
			case "external" -> new ExternalInstanceManager(this);
			default -> throw new MDTInstanceManagerException("Unknown MDTInstanceManager type: "
															+ mgrConfig.getType());
		};

		s_logger.info("use MDTInstanceManager: {}", instManager);
		
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
	public EntityManagerFactory getEntityManagerFactory() {
		if ( s_logger.isInfoEnabled() ) {
			JdbcConfiguration jdbcConf = getJpaConfig().getJdbc();
			s_logger.info("JDBCConfiguration: url={}, user={}, password={}",
							jdbcConf.getJdbcUrl(), jdbcConf.getUser(), jdbcConf.getPassword());
			
			Map<String,String> jpaProps = getJpaConfig().getProperties();
			s_logger.info("JPA Properties: {}", jpaProps);
		}
		
//		return Persistence.createEntityManagerFactory("MDTInstanceManager");
		InstancePersistenceUnitInfo pUnitInfo = new InstancePersistenceUnitInfo(getJpaConfig());
		return new HibernatePersistenceProvider()
					.createContainerEntityManagerFactory(pUnitInfo, Maps.newHashMap());
	}
	
//	@Autowired private MDTWorkflowManagerConfiguration m_workflowConf;
//	@Bean
//	WorkflowManager getWorkflowManager() {
//		WorkflowModelManager modelMgr = new JpaWorkflowModelManager(getEntityManagerFactory());
//		return new OpenApiArgoWorkflowManager(modelMgr, m_workflowConf);
//	}
}
