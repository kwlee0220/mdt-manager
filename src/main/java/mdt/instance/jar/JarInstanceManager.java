package mdt.instance.jar;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.io.Files;

import utils.InternalException;
import utils.func.Tuple;

import mdt.Globals;
import mdt.MDTConfiguration;
import mdt.exector.jar.JarExecutionListener;
import mdt.exector.jar.JarInstanceExecutor;
import mdt.instance.AbstractInstanceManager;
import mdt.instance.InstanceStatusChangeEvent;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.JarExecutionArguments;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JarInstanceManager extends AbstractInstanceManager<JarInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstanceManager.class);
	
	private final JarInstanceExecutor m_executor;
	private final File m_defaultInstanceJarFile;
	
	public JarInstanceManager(MDTConfiguration conf) {
		super(conf);
		setLogger(s_logger);
		
		m_defaultInstanceJarFile = conf.getJarExecutorConfiguration().getDefaultMDTInstanceJarFile();
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("use default MDTInstance jar file: {}", m_defaultInstanceJarFile.getAbsolutePath());
		}

		m_executor = new JarInstanceExecutor(conf.getJarExecutorConfiguration());
		m_executor.addExecutionListener(m_execListener);
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
	
	public JarExecutionArguments parseExecutionArguments(String argsJson) {
		try {
			return m_mapper.readValue(argsJson, JarExecutionArguments.class);
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException("Failed to parse JarExecutionArguments string, cause=" + e);
		}
	}
	
	public String toExecutionArgumentsString(JarExecutionArguments args) {
		try {
			return m_mapper.writeValueAsString(args);
		}
		catch ( JsonProcessingException e ) {
			throw new InternalException("Failed to write JarExecutionArguments string, cause=" + e);
		}
	}
	
	public void shutdown() {
		m_executor.shutdown();
	}
	
	@Override
	protected JarInstance toInstance(JpaInstanceDescriptor descriptor) throws MDTInstanceManagerException {
		return new JarInstance(this, descriptor);
	}
	
	@Override
	protected JpaInstanceDescriptor initializeInstance(JpaInstanceDescriptor desc) {
		File instanceDir = getInstanceHomeDir(desc.getId());
		try {
			// 동일 이름의 directory가 존재할 수도 있기 때문에 해당 디렉토리가 있으면
			// 먼저 삭제하고, instance용 workspace directory를 생성한다.
			FileUtils.deleteDirectory(instanceDir);
			instanceDir.mkdirs();
			
			// Jar 파일은 workspace directory로 복사한다.
			File jarFile = new File(instanceDir, FA3ST_JAR_FILE_NAME);
			
			// configuration 파일들을 configs directory로 복사시킨다.
			File modelFile = new File(instanceDir, MODEL_FILE_NAME);
			File confFile = new File(instanceDir, CONF_FILE_NAME);
			File certFile = new File(instanceDir, CERT_FILE_NAME);
			File globalConfFile = new File(instanceDir, GLOBAL_CONF_FILE_NAME);
			
			JarExecutionArguments jargs = m_mapper.readValue(desc.getArguments(),
															JarExecutionArguments.class);
			
			String jarFilePath = FA3ST_JAR_FILE_NAME;
			if ( jargs.getJarFile() != null ) {
				copyFileIfNotSame(new File(jargs.getJarFile()), jarFile);
			}
			else {
				if ( m_defaultInstanceJarFile == null || !m_defaultInstanceJarFile.exists() ) {
					throw new IllegalStateException("No default MDTInstance jar file exists: path="
													+ m_defaultInstanceJarFile);
				}
				jarFilePath = this.m_defaultInstanceJarFile.getAbsolutePath();
			}
			copyFileIfNotSame(new File(jargs.getModelFile()), modelFile);
			copyFileIfNotSame(new File(jargs.getConfigFile()), confFile);
			jargs = JarExecutionArguments.builder()
										.jarFile(jarFilePath)
										.modelFile(MDTInstanceManager.MODEL_FILE_NAME)
										.configFile(CONF_FILE_NAME)
										.build();
			desc.setArguments(m_mapper.writeValueAsString(jargs));
			
			File defaultCertFile = new File(getHomeDir(), CERT_FILE_NAME);
			Files.copy(defaultCertFile, certFile);
			
			File srcGlobalConfFile = new File(getHomeDir(), GLOBAL_CONF_FILE_NAME);
			if ( srcGlobalConfFile.exists() ) {
				Files.copy(srcGlobalConfFile, globalConfFile);
			}
			
			return desc;
		}
		catch ( IOException e ) {
			throw new MDTInstanceManagerException("Failed to initialize MDTInstance: descriptor="
													+ desc + ", cause=" + e);
		}
	}
	
	private void copyFileIfNotSame(File src, File dest) throws IOException {
		if ( !src.getAbsolutePath().equals(dest.getAbsolutePath()) ) {
			Files.copy(src, dest);
		}
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
