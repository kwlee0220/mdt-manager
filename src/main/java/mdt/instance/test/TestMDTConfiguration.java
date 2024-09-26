package mdt.instance.test;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import org.hibernate.jpa.HibernatePersistenceProvider;

import com.google.common.collect.Maps;

import jakarta.persistence.EntityManagerFactory;
import mdt.MDTConfiguration;
import mdt.instance.jpa.InstancePersistenceUnitInfo;
import mdt.model.InvalidIdShortPathException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TestMDTConfiguration extends MDTConfiguration {
	public MqttConfiguration getMqttConfiguration() {
		MqttConfiguration c = new MqttConfiguration();
		c.setClientId("MDTInstanceManager");
		c.setEndpoint("tcp://localhost:1883");
		
		return c;
	}
	
	public JarExecutorConfiguration getJarExecutorConfiguration() {
		JarExecutorConfiguration c = new JarExecutorConfiguration();
		c.setWorkspaceDir(new File("C:\\Temp\\Test"));
		c.setSampleInterval(Duration.ofSeconds(3));
		c.setStartTimeout(Duration.ofMinutes(1));
		
		return c;
	}
	
	public EntityManagerFactory getEntityManagerFactory() {
		Map<String,String> props = Maps.newHashMap();
		props.put("hibernate.show_sql", "false");
		props.put("hibernate.format_sql", "true");
		props.put("hibernate.hbm2ddl.auto", "create");
		
		JdbcConfiguration jdbcConfig = new JdbcConfiguration();
		jdbcConfig.setUrl("jdbc:h2:~/mdt-descriptors");
		jdbcConfig.setUser("sa");
		jdbcConfig.setPassword("");
		
		JpaConfiguration jpaConf = new JpaConfiguration();
		jpaConf.setJdbc(jdbcConfig);
		jpaConf.setProperties(props);
		
		InstancePersistenceUnitInfo pUnitInfo = new InstancePersistenceUnitInfo(jpaConf);
		return new HibernatePersistenceProvider()
					.createContainerEntityManagerFactory(pUnitInfo, Maps.newHashMap());
	}
}
