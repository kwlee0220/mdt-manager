package mdt.instance.jar;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import utils.InternalException;
import utils.func.Tuple;
import utils.io.FileUtils;

import mdt.Globals;
import mdt.MDTConfiguration;
import mdt.MDTConfiguration.JarExecutorConfiguration;
import mdt.exector.jar.JarExecutionListener;
import mdt.exector.jar.JarInstanceExecutor;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.InstanceDescriptorManager;
import mdt.instance.JpaInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.ModelValidationException;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JarInstanceManager extends AbstractInstanceManager<JpaInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstanceManager.class);
	
	private final JarInstanceExecutor m_executor;
	private final File m_instancesDir;
	private final File m_defaultInstanceJarFile;
	
	public JarInstanceManager(MDTConfiguration conf) {
		super(conf);
		setLogger(s_logger);
		
		m_defaultInstanceJarFile = conf.getJarExecutorConfiguration().getDefaultMDTInstanceJarFile();
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("use default MDTInstance jar file: {}", m_defaultInstanceJarFile.getAbsolutePath());
		}
		
		JarExecutorConfiguration execConf = conf.getJarExecutorConfiguration();
		m_instancesDir = execConf.getWorkspaceDir();

		m_executor = new JarInstanceExecutor(conf.getJarExecutorConfiguration());
		m_executor.addExecutionListener(m_execListener);
	}

	@Override
	public void initialize(InstanceDescriptorManager instDescManager) throws MDTInstanceManagerException {
		for ( JpaInstanceDescriptor desc: instDescManager.getInstanceDescriptorAll() ) {
			desc.setStatus(MDTInstanceStatus.STOPPED);
			desc.setBaseEndpoint(null);
		}
    	
    	try {
			if ( getInstancesDir().exists() ) {
				FileUtils.createDirectories(getInstancesDir());
			}
		}
		catch ( IOException e ) {
			throw new MDTInstanceManagerException(e);
		}
	}
	
	public File getInstancesDir() {
		return m_instancesDir;
	}
	
	public File getInstanceHomeDir(String id) {
		return FileUtils.path(getInstancesDir(), id);
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
	public InstanceDescriptor addInstance(String id, int faaastPort, File bundleDir)
		throws ModelValidationException, IOException {
		Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADDING(id));

		try {
			// bundle directory 전체가 해당 instance의 workspace가 되기 때문에
			// instances 디렉토리로 이동시킨다.
			File instDir = getInstanceHomeDir(id);
			FileUtils.deleteDirectory(instDir);
			FileUtils.move(bundleDir, instDir);
			
			JarExecutionArguments args = JarExecutionArguments.builder()
																.jarFile(FA3ST_JAR_FILE_NAME)
																.port(faaastPort)
																.build();
			
			File modelFile = FileUtils.path(instDir, MODEL_AASX_NAME);
			if ( !modelFile.canRead() ) {
				modelFile = FileUtils.path(instDir, MODEL_FILE_NAME);
			}
			String arguments = m_mapper.writeValueAsString(args);
			
			InstanceDescriptor desc = addInstanceDescriptor(id, modelFile, arguments);
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADDED(id));
			
			return desc;
		}
		catch ( Throwable e ) {
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.ADD_FAILED(id));
			throw new InternalException("Failed to add MDTInstance: id=" + id, e);
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
