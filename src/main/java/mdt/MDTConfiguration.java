package mdt;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import javax.annotation.Nullable;

import org.hibernate.jpa.HibernatePersistenceProvider;
import org.mandas.docker.client.exceptions.DockerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.common.collect.Maps;

import jakarta.persistence.EntityManagerFactory;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import mdt.client.HttpServiceFactory;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.docker.DockerConfiguration;
import mdt.instance.docker.DockerInstanceManager;
import mdt.instance.jar.JarInstanceManager;
import mdt.instance.jpa.InstancePersistenceUnitInfo;
import mdt.instance.k8s.KubernetesInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;

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

	@Data
	public class MDTInstanceManagerConfiguration {
		private String type;
		private String repositoryEndpointFormat;
		private File homeDir;
		private File instancesDir;
		private String modelIdTemplate;
	}
	@Bean
	@ConfigurationProperties(prefix = "instance-manager")
	public MDTInstanceManagerConfiguration getMDTInstanceManagerConfiguration() {
		return new MDTInstanceManagerConfiguration();
	}

	@Bean
	AbstractInstanceManager getMDTInstanceManager() throws DockerException, InterruptedException {
		MDTInstanceManagerConfiguration conf = getMDTInstanceManagerConfiguration();
		switch ( conf.getType() ) {
			case "jar":
				return new JarInstanceManager(this);
			case "docker":
				return new DockerInstanceManager(this);
			case "kubernetes":
				return new KubernetesInstanceManager(this);
			default:
				throw new MDTInstanceManagerException("Unknown MDTInstanceManager type: " + conf.getType());
		}
	}

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
		private File defaultMDTInstanceJarFile;
		private String heapSize;
	}

	@Bean
	@ConfigurationProperties(prefix = "docker")
	public DockerConfiguration getDockerConfiguration() {
		return new DockerConfiguration();
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
}