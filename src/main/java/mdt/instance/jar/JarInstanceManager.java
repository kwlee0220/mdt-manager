package mdt.instance.jar;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.io.IOExceptionList;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;

import utils.InternalException;
import utils.Throwables;
import utils.io.FileUtils;

import mdt.exector.jar.JarExecutionListener;
import mdt.exector.jar.JarInstanceExecutor;
import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.MqttConfiguration;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.ModelValidationException;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.repository.Repositories;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Component
@ConditionalOnProperty(prefix="instance-manager", name = "type", havingValue = "jar")
public class JarInstanceManager extends AbstractJpaInstanceManager<JpaInstance> {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstanceManager.class);
	private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);
	private static final Duration DEFAULT_START_TIMEOUT = Duration.ofMinutes(1);
	
	private final JarInstanceExecutor m_executor;
	private final File m_defaultInstanceJarFile;
	
	public JarInstanceManager(MDTInstanceManagerConfiguration mgrConf,
								Repositories repos,
								JarExecutorConfiguration jarExecConf,
								MqttConfiguration mqttConf) throws Exception {
		super(mgrConf, repos, mqttConf);
		setLogger(s_logger);

		m_defaultInstanceJarFile = jarExecConf.getDefaultMDTInstanceJarFile();
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("use default MDTInstance jar file: {}", m_defaultInstanceJarFile.getAbsolutePath());
		}

		m_executor = new JarInstanceExecutor(mgrConf, jarExecConf);
		m_executor.addExecutionListener(m_execListener);
		
		repos.instances().resetAll();
	}
	
	public JarInstanceExecutor getInstanceExecutor() {
		return m_executor;
	}
	
	public void shutdown() {
		m_executor.shutdown();
	}
	
	public void startInstanceAll() {
		try ( ExecutorService exector = Executors.newFixedThreadPool(3) ) {
			for ( JpaInstanceDescriptor desc: m_repos.instances().findAll() ) {
				CompletableFuture.runAsync(() -> {
					try {
						JpaInstance inst = getInstance(desc.getId());
						inst.start(DEFAULT_POLL_INTERVAL, DEFAULT_START_TIMEOUT);
					}
					catch ( Throwable e ) {
						Throwable cause = Throwables.unwrapThrowable(e);
						getLogger().warn("Failed to start JarInstance: id={}, cause={}", desc.getId(), cause);
					}
				}, exector);
			}
		}
	}

	@Override
	public MDTInstance addInstance(String id, int port, File bundleDir)
		throws ModelValidationException, IOException, MDTInstanceManagerException {
		try {
			// bundle directory 전체가 해당 instance의 workspace가 되기 때문에
			// instances 디렉토리로 이동시킨다.
			File instDir = getInstanceHomeDir(id);
			FileUtils.deleteDirectory(instDir);
			FileUtils.copyDirectory(bundleDir, instDir);
			
			// mdt-instance-all.jar file이 없는 경우에는 default jar 파일을 사용한다.
			File instanceJarFile = new File(instDir, MDTInstanceManager.MDT_INSTANCE_JAR_FILE_NAME);
			if ( !instanceJarFile.exists() ) {
				File defaultJarFile = m_defaultInstanceJarFile;
				if ( defaultJarFile == null || !defaultJarFile.exists() ) {
					throw new IllegalStateException("No default MDTInstance jar file exists: path=" + defaultJarFile);
				}
				
				FileUtils.copyFile(defaultJarFile, instanceJarFile);
			}
			
			JarExecutionArguments args = new JarExecutionArguments();
			args.setJarFile(instanceJarFile.getAbsolutePath());
			args.setPort(port);
			
			File modelFile = FileUtils.path(instDir, MODEL_AASX_NAME);
			if ( !modelFile.canRead() ) {
				modelFile = FileUtils.path(instDir, MODEL_FILE_NAME);
			}
			Environment env = AASUtils.readEnvironment(modelFile);
			String arguments = m_mapper.writeValueAsString(args);
			
			JpaInstanceDescriptor desc = addInstanceDescriptor(id, env, arguments);
			getLogger().info("added JarInstance: id={}, instanceDir={}", desc.getId(), instDir);
			
			return toInstance(desc);
		}
		catch ( IOExceptionList e) {
			Throwable clause = e.getCause(0);
			throw new IOException("Failed to add MDTInstance: id=" + id + ", cause=" + clause);
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

	@Override
	protected void adaptInstanceDescriptor(JpaInstanceDescriptor desc) { }
	
	// 프로세스의 상태 변화에 따라 InstanceDescriptor의 상태를 업데이트하는 모듈
	private final JarExecutionListener m_execListener = new JarExecutionListener() {
		@Override
		public void stausChanged(String id, MDTInstanceStatus status, String endpoint) {
			JarInstanceManager.this.updateInstanceDescriptor(id, status, endpoint);
		}
		
		@Override
		public void timeoutExpired() { }
	};
	
//	private final JarExecutionListener m_execListener = new JarExecutionListener() {
//		@Override
//		public void stausChanged(String id, MDTInstanceStatus status, int repoPort) {
//			switch ( status ) {
//				case RUNNING:
//					String svcEp = toServiceEndpoint(repoPort);
//					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.RUNNING(id, svcEp));
//					break;
//				case STOPPED:
//					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPED(id));
//					break;
//				case FAILED:
//					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.FAILED(id));
//					break;
//				case STARTING:
//					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STARTING(id));
//					break;
//				case STOPPING:
//					Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPING(id));
//					break;
//				default:
//					throw new InternalException("JarExecutor throws an unknown status: " + status);
//			}
//		}
//		
//		@Override
//		public void timeoutExpired() {
//		}
//	};
}
