package mdt.instance.jar;

import java.io.File;
import java.io.IOException;

import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;

import utils.InternalException;
import utils.Throwables;
import utils.func.Tuple;
import utils.io.FileUtils;

import mdt.Globals;
import mdt.MDTConfiguration;
import mdt.exector.jar.JarExecutionListener;
import mdt.exector.jar.JarInstanceExecutor;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.InstanceDescriptorManager;
import mdt.instance.JpaInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager;
import mdt.instance.jpa.JpaProcessor;
import mdt.model.AASUtils;
import mdt.model.ModelValidationException;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JarInstanceManager extends AbstractJpaInstanceManager<JpaInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstanceManager.class);
	
	private final JarInstanceExecutor m_executor;
	private final File m_defaultInstanceJarFile;
	
	public JarInstanceManager(MDTConfiguration conf) {
		super(conf);
		setLogger(s_logger);
		
		m_defaultInstanceJarFile = conf.getJarExecutorConfiguration().getDefaultMDTInstanceJarFile();
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("use default MDTInstance jar file: {}", m_defaultInstanceJarFile.getAbsolutePath());
		}

		m_executor = new JarInstanceExecutor(conf.getJarExecutorConfiguration());
		m_executor.addExecutionListener(m_execListener);
	}

	@Override
	public void initialize(InstanceDescriptorManager instDescManager, JpaProcessor jpaProcessor)
		throws MDTInstanceManagerException {
		Preconditions.checkArgument(instDescManager instanceof JpaInstanceDescriptorManager,
									"JpaInstanceDescriptorManager required");
		JpaInstanceDescriptorManager jpaInstDescManager = (JpaInstanceDescriptorManager)instDescManager;
		Preconditions.checkArgument(jpaInstDescManager.getEntityManager() != null, "EntityManager is not assigned");
		
		super.initialize(instDescManager, jpaProcessor);
		
		for ( JpaInstanceDescriptor desc: instDescManager.getInstanceDescriptorAll() ) {
			desc.setStatus(MDTInstanceStatus.STOPPED);
			desc.setBaseEndpoint(null);
		}
	}
	
	public JarInstanceExecutor getInstanceExecutor() {
		return m_executor;
	}
	
	@Override
	public MDTInstanceStatus getInstanceStatus(String id) {
		return m_executor.getStatus(id)._1;
	}

	@Override
	public String getInstanceServiceEndpoint(String id) {
		Tuple<MDTInstanceStatus,Integer> result = m_executor.getStatus(id);
		return (result._2 > 0) ? toServiceEndpoint(result._2) : null;
	}
	
	public void shutdown() {
		m_executor.shutdown();
	}

	@Override
	public MDTInstance addInstance(String id, int faaastPort, File bundleDir)
		throws ModelValidationException, IOException, MDTInstanceManagerException {
		Preconditions.checkArgument(faaastPort > 0);
		
		try {
			// bundle directory 전체가 해당 instance의 workspace가 되기 때문에
			// instances 디렉토리로 이동시킨다.
			File instDir = getInstanceHomeDir(id);
			FileUtils.deleteDirectory(instDir);
			FileUtils.move(bundleDir, instDir);
			
			// FA3ST jar file이 없는 경우에는 default jar 파일을 사용한다.
			File fa3stJarFile = new File(instDir, MDTInstanceManager.FA3ST_JAR_FILE_NAME);
			if ( !fa3stJarFile.exists() ) {
				File defaultJarFile = m_defaultInstanceJarFile;
				if ( defaultJarFile == null || !defaultJarFile.exists() ) {
					throw new IllegalStateException("No default MDTInstance jar file exists: path=" + defaultJarFile);
				}
				
				FileUtils.copy(defaultJarFile, fa3stJarFile);
			}

			// Global 설정 파일이 없는 경우에는 default 설정 파일을 사용한다.
			File globalConfFile = new File(instDir, MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
			if ( !globalConfFile.exists() ) {
				File defaultGlobalConfFile = new File(getHomeDir(), MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
				if ( !defaultGlobalConfFile.exists()) {
					throw new IllegalStateException("No default global configuration file exists: path="
														+ defaultGlobalConfFile);
				}
				FileUtils.copy(defaultGlobalConfFile, globalConfFile);
			}
			
			// Certificate 파일이 없는 instDir default 파일을 사용한다.
			File certFile = new File(instDir, MDTInstanceManager.CERT_FILE_NAME);
			if ( !certFile.exists() ) {
				File defaultCertFile = new File(getHomeDir(), MDTInstanceManager.CERT_FILE_NAME);
				if ( !defaultCertFile.exists() ) {
					throw new IllegalStateException("No default certificate file exists: path=" + defaultCertFile);
				}
				FileUtils.copy(defaultCertFile, certFile);
			}
			
			JarExecutionArguments args = JarExecutionArguments.builder()
																.jarFile(FA3ST_JAR_FILE_NAME)
																.port(faaastPort)
																.build();
			
			File modelFile = FileUtils.path(instDir, MODEL_AASX_NAME);
			if ( !modelFile.canRead() ) {
				modelFile = FileUtils.path(instDir, MODEL_FILE_NAME);
			}
			Environment env = AASUtils.readEnvironment(modelFile);
			String arguments = m_mapper.writeValueAsString(args);
			
			JpaInstanceDescriptor desc = addInstanceDescriptor(id, env, arguments);
			if ( getLogger().isInfoEnabled() ) {
				getLogger().info("added JarInstance: id={}, port={}, instanceDir={}",
									desc.getId(), faaastPort, instDir);
			}
			
			return toInstance(desc);
		}
		catch ( IOException | ModelValidationException | MDTInstanceManagerException e ) {
			throw e;
		}
		catch ( Throwable e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new MDTInstanceManagerException("Failed to add MDTInstance: id=" + id, cause);
		}
	}

	@Override
	protected JarInstance toInstance(JpaInstanceDescriptor descriptor) {
		return new JarInstance(this, descriptor);
	}
	
	public JarExecutionArguments parseExecutionArguments(String argsJson) {
		try {
			return m_mapper.readValue(argsJson, JarExecutionArguments.class);
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException("Failed to parse JarExecutionArguments string, cause=" + e);
		}
	}
	
	@Override
	public String toString() {
		return String.format("%s[home=%s]", getClass().getSimpleName(), getInstancesDir());
	}
	
	private final JarExecutionListener m_execListener = new JarExecutionListener() {
		@Override
		public void stausChanged(String id, MDTInstanceStatus status, int repoPort) {
			switch ( status ) {
				case RUNNING:
					String svcEp = toServiceEndpoint(repoPort);
					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.RUNNING(id, svcEp));
					break;
				case STOPPED:
					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPED(id));
					break;
				case FAILED:
					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.FAILED(id));
					break;
				case STARTING:
					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STARTING(id));
					break;
				case STOPPING:
					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPING(id));
					break;
				default:
					throw new InternalException("JarExecutor throws an unknown status: " + status);
			}
		}
		
		@Override
		public void timeoutExpired() {
		}
	};
}
