package mdt.exector.jar;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import utils.KeyValue;
import utils.StopWatch;
import utils.Throwables;
import utils.Tuple;
import utils.UnitUtils;
import utils.async.Guard;
import utils.func.FOption;
import utils.func.Try;
import utils.func.Unchecked;
import utils.io.EnvironmentFileLoader;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.io.LogTailer;
import utils.stream.FStream;

import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.jar.JarExecutionArguments;
import mdt.instance.jar.JarExecutorConfiguration;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
public class JarInstanceExecutor {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstanceExecutor.class);
	
	private static final String DEFAULT_HEAP_SIZE = "512m";
	
	private final MDTInstanceManagerConfiguration m_mgrConfig;
	private final JarExecutorConfiguration m_execConfig;
	private final File m_workspaceDir;
	private final Semaphore m_startSemaphore;	// 동시에 시작할 수 있는 프로세스 수 제한
	
	private final Guard m_guard = Guard.create();
	// 이 JarInstanceExecutor를 통해 실행 중인 모든 프로세스들의 등록정보
	private final Map<String,ProcessDesc> m_runningInstances = Maps.newHashMap();
	private final Set<JarExecutionListener> m_listeners = Sets.newConcurrentHashSet();

	public JarInstanceExecutor(MDTInstanceManagerConfiguration mgrConf, JarExecutorConfiguration conf) throws MDTInstanceManagerException {
		Preconditions.checkArgument(conf != null, "JarExecutorConfiguration is null");
		Preconditions.checkArgument(conf.getWorkspaceDir() != null, "JarExecutorConfiguration.workspaceDir is null");
		
		m_mgrConfig = mgrConf;
		m_execConfig = conf;
		m_workspaceDir = conf.getWorkspaceDir();
		Try.accept(m_workspaceDir, FileUtils::createDirectory);
		
		m_startSemaphore = new Semaphore(conf.getStartConcurrency());
	}
	
	public File getWorkspaceDir() {
		return m_workspaceDir;
	}
	
	public Tuple<MDTInstanceStatus,String> start(String id, String aasId, JarExecutionArguments args)
		throws MDTInstanceExecutorException {
		try {
			// 동시에 실행할 수 있는 프로세스 수 제한을 위해 semaphore를 획득한다.
			m_startSemaphore.acquire();
			s_logger.debug("acquired start-semaphore: thead={}", Thread.currentThread().getName());
		}
		catch ( InterruptedException e ) {
			throw new MDTInstanceExecutorException("" + e);
		}
		
		try {
			return startWithSemaphore(id, aasId, args);
		}
		finally {
			m_startSemaphore.release();
			s_logger.debug("released a start-semaphore: thead={}", Thread.currentThread().getName());
		}
	}
	
	private Tuple<MDTInstanceStatus,String> startWithSemaphore(String id, String aasId, JarExecutionArguments args)
		throws MDTInstanceExecutorException {
    	File jobDir = new File(m_workspaceDir, id);
		File logDir = new File(jobDir, "logs");
		
		// 혹시나 있지 모를 'logs' 디렉토리 삭제.
    	Try.accept(logDir, FileUtils::deleteDirectory);
    	
    	String heapSize = FOption.getOrElse(m_execConfig.getHeapSize(), DEFAULT_HEAP_SIZE);
    	String initialHeap = String.format("-Xms%s", heapSize);
    	String maxHeap = String.format("-Xmx%s", heapSize);
    	String encoding = "-Dfile.encoding=UTF-8";
    	
    	String argHomeDir = String.format("--home=%s/%s", m_workspaceDir.getAbsolutePath(), id);
    	String argId = String.format("--id=%s", id);
    	String argPort = (args.getPort() > 0) ? String.format("--port=%d", args.getPort()) : "";
    	String argManagerEndpoint = String.format("--managerEndpoint=%s", m_mgrConfig.getEndpoint());
    	String argType = String.format("--type=jar");
//    	String argVerbose = "-v";
//    	String noValid = "--no-validation";
    	
    	File configFile = new File(jobDir, "config.json");
    	if ( !configFile.exists() ) {
    		try {
    			s_logger.warn("creating an empty config file: {}", configFile);
				IOUtils.toFile("{}", configFile);
			}
			catch ( IOException e ) {
				throw new MDTInstanceExecutorException("failed to create a config file: " + configFile
														+ ", cause=" + e);
			}
    	}

    	List<String> argList = Lists.newArrayList("java", encoding, initialHeap, maxHeap,
    												"-jar", args.getJarFile(), argId, argManagerEndpoint,
    												argType, argHomeDir);
    	if ( argPort.length() > 0 ) {
			argList.add(argPort);
		}
    	if ( m_mgrConfig.getGlobalConfigFile() != null ) {
        	String globalConfigFilePath = String.format("--globalConfig=%s",
        												m_mgrConfig.getGlobalConfigFile().getAbsolutePath());
        	argList.add(globalConfigFilePath);
    	}
    	if ( m_execConfig.getKeyStoreFile() != null ) {
        	String argKeyStorePath = String.format("--keyStore=%s", m_execConfig.getKeyStoreFile().getAbsolutePath());  
        	argList.add(argKeyStorePath);
    	}
    	if ( m_execConfig.getKeyStorePassword() != null ) {
			String argKeyStorePwd = String.format("--keyStorePassword=%s", m_execConfig.getKeyStorePassword());
			argList.add(argKeyStorePwd);
		}
    	
		ProcessBuilder builder = new ProcessBuilder(argList);
		builder.directory(jobDir);
		
		Map<String,String> envVars = builder.environment();
		
		// 환경 변수 파일에서 환경변수들을 로드하여 설정
		Map<String,String> udEnvVars = Maps.newHashMap();
		try {
			File envFile = new File(jobDir, "env.file");
			udEnvVars = EnvironmentFileLoader.from(envFile).load();
			envVars.putAll(udEnvVars);
		}
		catch ( IOException ignored ) { }
		
		s_logger.debug("creating MDTInstance: workDir={}, args={}, envs={}",
						m_workspaceDir.getAbsolutePath(), argList, udEnvVars);

		File stdoutLogFile = new File(logDir, "output.log");
		builder.redirectErrorStream(true);
		builder.redirectOutput(Redirect.appendTo(stdoutLogFile));

		ProcessDesc procDesc = new ProcessDesc(id, null, MDTInstanceStatus.STARTING, null, stdoutLogFile);
		m_guard.run(() -> {
			m_runningInstances.put(id, procDesc);
			notifyStatusChanged(procDesc);
		});
		
		try {
			Files.createDirectories(logDir.toPath());
			
			Process instanceProcess = builder.start();
			instanceProcess.onExit()
							.whenCompleteAsync((proc, error) -> onProcessTerminated(procDesc, error));
			m_guard.run(() -> procDesc.m_process = instanceProcess);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			s_logger.warn("failed to start jar application: id={}, argList={}, cause={}",
							id, argList, ""+cause);
			m_guard.run(() -> {
				procDesc.m_status = MDTInstanceStatus.FAILED;
				procDesc.m_endpoint = null;
				notifyStatusChanged(procDesc);
			});
			
			if ( s_logger.isDebugEnabled() ) {
				s_logger.debug("releasing a start semaphore due to failure: thead={}",
								Thread.currentThread().getName());
			}
			
			return Tuple.of(procDesc.m_status, procDesc.m_endpoint);
		}
		
		// 프로세스를 시작시킨 후, 출력 메시지를 검사하여 서비스 포트가 오픈될 때까지 대기한다.
		return waitWhileStarting(id, procDesc);
	}

    public Tuple<MDTInstanceStatus,String> stop(final String instanceId) {
    	ProcessDesc procDesc = m_guard.get(() -> {
        	ProcessDesc desc = m_runningInstances.get(instanceId);
        	if ( desc != null ) {
        		switch ( desc.m_status ) {
        			case RUNNING:
        			case STARTING:
                    	if ( s_logger.isDebugEnabled() ) {
                    		s_logger.debug("stopping MDTInstance: {}", instanceId);
                    	}
                		desc.m_status = MDTInstanceStatus.STOPPING;
        				desc.m_endpoint = null;
                		notifyStatusChanged(desc);
                		
//            			Executions.runAsync(() -> waitWhileStopping(instanceId, desc));
//                		desc.m_process.destroy();
                		CompletableFuture.runAsync(() -> desc.m_process.destroy());
                		break;
                	default:
                		return null;
        		}
        	}
        	return desc;
    	});
    	
    	return (procDesc != null) ? procDesc.toResult() : null;
    }

    public Tuple<MDTInstanceStatus,String> getStatus(String instanceId) {
    	return m_guard.get(() -> {
    		ProcessDesc desc = m_runningInstances.get(instanceId);
        	if ( desc != null ) {
        		return desc.toResult();
        	}
        	else {
        		return Tuple.of(MDTInstanceStatus.STOPPED, null);
        	}
    	});
    }
	
	public boolean addExecutionListener(JarExecutionListener listener) {
		return m_guard.get(() -> m_listeners.add(listener));
	}
	public boolean removeExecutionListener(JarExecutionListener listener) {
		return m_guard.get(() -> m_listeners.remove(listener));
	}
	
	public String getOutputLog(String id) throws IOException {
		File logDir = new File(new File(m_workspaceDir, id), "logs");
		File stdoutLogFile = new File(logDir, "output.log");
		
		if ( !stdoutLogFile.canRead() ) {
			throw new IOException("Cannot read stdout log file: path=" + stdoutLogFile.getAbsolutePath());
		}
		
		return Files.readString(stdoutLogFile.toPath(), StandardCharsets.UTF_8);
	}
	
	public void shutdown() {
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("Shutting down JarInstanceExecutor...");
		}

		int remains = 0;
		StopWatch watch = StopWatch.start();
		remains = m_guard.get(() -> {
			FStream.from(m_runningInstances.keySet())
					.forEach(id -> {
						if ( s_logger.isInfoEnabled() ) {
							s_logger.info("shutting-down JarInstance: {}", id);
						}
						stop(id);
					});
			return m_runningInstances.size();
		});
		while ( true ) {
			try {
				if ( remains == 0 ) {
					String elapsedStr = watch.stopAndGetElpasedTimeString();
					if ( s_logger.isInfoEnabled() ) {
						s_logger.info("elapsed in shutting down instances: {}s", elapsedStr);
					}
					
					return;
				}
				if ( watch.getElapsedInMillis() > 5000 ) {
					if ( s_logger.isInfoEnabled() ) {
						s_logger.warn("MDTInstances are still remains stopping : {}s", remains);
					}
					break;
				}
				Thread.sleep(100);
				remains = m_guard.get(() -> m_runningInstances.size());
			}
			catch ( InterruptedException expected ) { }
		}
	}

	private static class ProcessDesc {
		private final String m_id;
		private Process m_process;
		private MDTInstanceStatus m_status;
		private String m_endpoint = null;
		private final File m_stdoutLogFile;
		
		public ProcessDesc(String id, Process process, MDTInstanceStatus status,
							String serviceEndpoint, File stdoutLogFile) {
			this.m_id = id;
			this.m_process = process;
			this.m_status = status;
			this.m_stdoutLogFile = stdoutLogFile;
		}
		
		public Tuple<MDTInstanceStatus,String> toResult() {
			return Tuple.of(m_status, m_endpoint);
		}
		
		@Override
		public String toString() {
			String pidStr = (m_process != null) ? (""+m_process.toHandle().pid()) : "N/A";
			return String.format("JarInstanceProcess(id=%s, proc=%s, status=%s, endpoint=%s)",
									m_id, pidStr, m_status, m_endpoint);
		}
	}
	
	private Tuple<MDTInstanceStatus,String> waitWhileStarting(final String instId, ProcessDesc procDesc) {
		Instant started = Instant.now();
		
		LogTailer tailer = LogTailer.builder()
									.file(procDesc.m_stdoutLogFile)
									.startAtBeginning(true)
									.sampleInterval(m_execConfig.getSampleInterval())
									.timeout(m_execConfig.getStartTimeout())
									.build();
		
		// 0: HTTP endpoint available on port (성공적으로 시작된 경우)
		// 1: ERROR (실패한 경우)
		List<String> sentinels = Arrays.asList("[***MARKER***]", "ERROR");
		SentinelFinder finder = new SentinelFinder(sentinels);
		tailer.addLogTailerListener(finder);
		
		try {
			// Sentinel 문자열 감시 작업 수행.
			// 이 작업은 SentinelFinder가 원하는 sentinel 문자열을 찾을 때까지 계속해서 실행된다.
			tailer.run();
			
			// sentinel 문자열을 찾은 경우에 대한 처리.
			// 또는 대기 시간이 경과한 경우에 대한 처리.
			
			final KeyValue<Integer,String> sentinel = finder.getSentinel();
			return m_guard.get(() -> {
				if ( sentinel != null && sentinel.key() == 0 ) {
					// 'HTTP endpoint available on port' sentinel을 찾은 경우.
					// 프로세스가 성공적으로 시작되었다고 간주한다.
					//
					if ( s_logger.isDebugEnabled() ) {
						s_logger.debug("found sentinel: {}", sentinel.value());
					}
					
					// 만일 프로세스가 sententinel을 출력한 이후에 종료된 경우에는
					// 실패로 간주해야 하기 때문에 프로세스의 상태를 점검한다.
					if ( procDesc.m_status != MDTInstanceStatus.STARTING ) {
						// 프로세스가 STARTING 상태가 아닌 경우에는 실패로 간주한다.
						s_logger.warn("MDTInstance has been started, but already terminated: id={}, status={}",
										instId, procDesc.m_status);
						return procDesc.toResult();
					}
					else {
						String[] parts = sentinel.value().split(" ");
						procDesc.m_endpoint = parts[parts.length-1];
						procDesc.m_status = MDTInstanceStatus.RUNNING;

						if ( s_logger.isInfoEnabled() ) {
							long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
				    		String elapsedStr = UnitUtils.toSecondString(elapsedMillis);
				    		s_logger.info("started MDTInstance: id={}, endpoint={}, elapsed={}",
				    						instId, procDesc.m_endpoint, elapsedStr);
						}
				    	
						notifyStatusChanged(procDesc);
					}
					
					return procDesc.toResult();
				}
				else {
					// 'ERROR' sentinel을 찾은 경우거나 다른 이유로 종료된 경우.
					// JarInstance 시작이 실패한 것으로 간주한다.
					//
			    	if ( s_logger.isInfoEnabled() ) {
			    		s_logger.info("failed to start an MDTInstance: {}", instId);
			    		s_logger.info("kill fa3st-repository process: {}", procDesc.m_process.toHandle().pid());
			    	}
					procDesc.m_status = MDTInstanceStatus.FAILED;
					procDesc.m_endpoint = null;
					
			    	// 프로세스가 수행 중인 상태이기 때문에 강제로 프로세스를 강제로 종료시킨다.
			    	procDesc.m_process.destroyForcibly();
			    	notifyStatusChanged(procDesc);
			    	
					return procDesc.toResult();
				}
			});
		}
		catch ( Exception e ) {
			m_guard.run(() -> procDesc.m_status = MDTInstanceStatus.FAILED);
			procDesc.m_process.destroyForcibly();
			
	    	if ( s_logger.isInfoEnabled() ) {
	    		s_logger.info("failed to start an MDTInstance: {}, cause={}", instId, e);
	    	}
	    	notifyStatusChanged(procDesc);
	    	
			return Tuple.of(MDTInstanceStatus.FAILED, null);
		}
	}
    
    private InstanceStatusChangeEvent waitWhileStopping(final String instId, ProcessDesc procDesc) {
		try {
			LogTailer tailer = LogTailer.builder()
										.file(procDesc.m_stdoutLogFile)
										.startAtBeginning(false)
										.sampleInterval(m_execConfig.getSampleInterval())
										.timeout(m_execConfig.getStartTimeout())
										.build();
			
			List<String> sentinels = Arrays.asList("Goodbye!");
			SentinelFinder finder = new SentinelFinder(sentinels);
			tailer.addLogTailerListener(finder);

			try {
				tailer.run();
				
				finder.getSentinel();
		    	if ( s_logger.isInfoEnabled() ) {
		    		s_logger.info("stopped MDTInstance: {}", instId);
		    	}
				m_guard.run(() -> {
					procDesc.m_status = MDTInstanceStatus.STOPPED;
					m_runningInstances.remove(instId);
				});
				return InstanceStatusChangeEvent.STOPPED(instId);
			}
			catch ( Exception e ) {
				m_guard.run(() -> procDesc.m_status = MDTInstanceStatus.FAILED);
				procDesc.m_process.destroyForcibly();
		    	if ( s_logger.isInfoEnabled() ) {
		    		s_logger.info("failed to stop MDTInstance gracefully: id={}, cause={}", instId, e);
		    	}
				return InstanceStatusChangeEvent.FAILED(instId);
			}
		}
		catch ( Exception e ) {
			// 지정된 시간 내에 원하는 sentinel이 발견되지 못하거나 대기 중에 쓰레드가 종료된 경우.
			m_guard.run(() -> {
				procDesc.m_status = MDTInstanceStatus.STOPPED;
				m_runningInstances.remove(instId);
			});
			procDesc.m_process.destroyForcibly();
			return InstanceStatusChangeEvent.STOPPED(instId);
		}
    }
	
	private void onProcessTerminated(ProcessDesc procDesc, Throwable error) {
		if ( error == null ) {
			// m_runningInstances에 등록되지 않은 process들은
			// 모두 성공적으로 종료된 것으로 간주한다.
			m_guard.run(() -> {
				procDesc.m_status = (procDesc.m_status == MDTInstanceStatus.STOPPING)
									? MDTInstanceStatus.STOPPED
									: MDTInstanceStatus.FAILED;
				procDesc.m_endpoint = null;
				m_runningInstances.remove(procDesc.m_id);
			});
	    	if ( s_logger.isInfoEnabled() ) {
	    		s_logger.info("stopped MDTInstance: {}", procDesc.m_id);
	    	}
	    	notifyStatusChanged(procDesc);
		}
		else {
			m_guard.run(() -> procDesc.m_status = MDTInstanceStatus.FAILED);
	    	if ( s_logger.isInfoEnabled() ) {
	    		s_logger.info("failed MDTInstance: {}, cause={}", procDesc.m_id, error);
	    	}
	    	notifyStatusChanged(procDesc);
		}
	}
	
	private void notifyStatusChanged(ProcessDesc pdesc) {
		Tuple<MDTInstanceStatus, String> result = pdesc.toResult();
    	for ( JarExecutionListener listener: m_listeners ) {
    		Unchecked.runOrIgnore(() -> listener.stausChanged(pdesc.m_id, result._1, result._2));
    	}
	}
	
//	@SuppressWarnings("null")
//	public Tuple<MDTInstanceStatus,String> waitWhileStarting(String id) throws InterruptedException {
//		return m_guard.awaitCondition(() -> {
//							// procDesc의 상태가 STARTING인 동안은 계속 대기한다.
//							ProcessDesc procDesc = m_runningInstances.get(id);
//							if ( s_logger.isDebugEnabled() ) {
//								if ( procDesc == null ) {
//									s_logger.debug("waitWhileStarting: id={}, not-found", id);
//								}
//								else {
//									s_logger.debug("waitWhileStarting: id={}, status={}", id, procDesc.m_status);
//								}
//							}
//							return procDesc != null || procDesc.m_status == MDTInstanceStatus.STARTING;
//				        })
//						.andGet(() -> {
//							ProcessDesc procDesc = m_runningInstances.get(id);
//							return (procDesc != null) ? Tuple.of(procDesc.m_status, procDesc.m_endpoint)
//									                    : Tuple.of(MDTInstanceStatus.STOPPED, null);
//						});
//	}
}
