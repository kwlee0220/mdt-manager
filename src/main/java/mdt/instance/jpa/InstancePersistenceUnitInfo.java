package mdt.instance.jpa;

import java.net.URL;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import org.hibernate.jpa.HibernatePersistenceProvider;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import utils.jdbc.JdbcConfiguration;

import mdt.JpaConfiguration;

import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class InstancePersistenceUnitInfo implements PersistenceUnitInfo {
	private static final String PU_NAME = "MDTInstanceManager";
	
	private static final List<String> ENTITY_CLASSE_NAMES = List.of(
		JpaInstanceDescriptor.class.getName(),
		JpaInstanceSubmodelDescriptor.class.getName(),
		JpaAASDescriptor.class.getName(),
		JpaAASSubmodelDescriptor.class.getName(),
		
		JpaAssetParameterDescriptor.class.getName(),
		JpaMDTOperationDescriptor.class.getName()
		
//		JpaWorkflowModel.class.getName()
	);
	
	private final String m_punitName;
	private final JpaConfiguration m_jpaConf;
	
	public InstancePersistenceUnitInfo(JpaConfiguration jpaConf) {
		m_punitName = PU_NAME;
		m_jpaConf = jpaConf;
	}

	@Override
	public String getPersistenceUnitName() {
		return m_punitName;
	}

	@Override
	public String getPersistenceProviderClassName() {
		return HibernatePersistenceProvider.class.getName();
	}

	@Override
	public PersistenceUnitTransactionType getTransactionType() {
		return PersistenceUnitTransactionType.RESOURCE_LOCAL;
	}

	@Override
	public DataSource getJtaDataSource() {
		JdbcConfiguration jdbcConf = m_jpaConf.getJdbc();
		
		HikariConfig hikariConfig = new HikariConfig();
		hikariConfig.setJdbcUrl(jdbcConf.getJdbcUrl());
		hikariConfig.setUsername(jdbcConf.getUser());
		hikariConfig.setPassword(jdbcConf.getPassword());
		
		return new HikariDataSource(hikariConfig);
	}

	@Override
	public List<String> getManagedClassNames() {
		return ENTITY_CLASSE_NAMES;
	}

	@Override
	public DataSource getNonJtaDataSource() {
		return null;
	}

	@Override
	public List<String> getMappingFileNames() {
		return null;
	}

	@Override
	public List<URL> getJarFileUrls() {
		return null;
	}

	@Override
	public URL getPersistenceUnitRootUrl() {
		return null;
	}

	@Override
	public boolean excludeUnlistedClasses() {
		return false;
	}

	@Override
	public SharedCacheMode getSharedCacheMode() {
		return null;
	}

	@Override
	public ValidationMode getValidationMode() {
		return null;
	}
	
	private Properties m_props = null;

	@Override
	public Properties getProperties() {
		if ( m_props == null ) {
			m_props = new Properties();
			m_props.putAll(m_jpaConf.getProperties());
		}
		
		return m_props;
	}

	@Override
	public String getPersistenceXMLSchemaVersion() {
		return null;
	}

	@Override
	public ClassLoader getClassLoader() {
		return null;
	}

	@Override
	public void addTransformer(ClassTransformer transformer) {
	}

	@Override
	public ClassLoader getNewTempClassLoader() {
		return null;
	}

}
