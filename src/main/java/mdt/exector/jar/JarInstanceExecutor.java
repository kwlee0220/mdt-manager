package mdt.exector.jar;

import java.io.File;
import java.io.FileNotFoundException;
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
import utils.func.Optionals;
import utils.func.Try;
import utils.func.Unchecked;
import utils.io.EnvironmentFileLoader;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.io.LogTailer;
import utils.stream.FStream;
import utils.stream.KeyValueFStream;
import utils.thread.Guard;

import mdt.controller.MDTManagerEnvironment;
import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.jar.JarExecutionArguments;
import mdt.instance.jar.JarExecutorConfiguration;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
 * 각 MDTInstance를 별도의 JVM 프로세스(JAR 실행)로 기동/중지하고 상태를 관리하는 실행기이다.
 * <p>
 * 본 클래스는 작업 디렉터리 아래 인스턴스별 하위 디렉터리를 두고, {@code java -jar} 명령으로
 * JAR을 기동한 뒤 표준 출력 로그에서 sentinel 문자열을 감시하여 시작 완료/실패를 판단한다.
 * 동시에 시작할 수 있는 프로세스 수는 {@link Semaphore}로 제한하며, 등록된
 * {@link JarExecutionListener}들에게 상태 변화를 알린다.
 * <p>
 * 실행 중인 각 프로세스의 상태는 내부 {@link ProcessDesc}에 보관되며, 모든 접근은
 * {@link Guard}로 동기화된다.
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

	/**
	 * 주어진 매니저/실행기 설정으로 {@link JarInstanceExecutor}를 생성한다.
	 * <p>
	 * 설정의 작업 디렉터리가 존재하지 않으면 새로 생성한다. 동시 시작 가능 프로세스 수는
	 * {@link JarExecutorConfiguration#getStartConcurrency()} 값으로 제한된다.
	 *
	 * @param mgrConf	{@link MDTInstanceManagerConfiguration}. MDT URL / 전역 설정 파일 등을 제공.
	 * @param conf		{@link JarExecutorConfiguration}. 작업 디렉터리/힙 크기/Key Store 등을 제공.
	 * @throws MDTInstanceManagerException	초기화 과정에서 오류가 발생한 경우.
	 * @throws IllegalArgumentException		{@code conf} 또는 {@code conf.workspaceDir}이 {@code null}인 경우.
	 */
	public JarInstanceExecutor(MDTInstanceManagerConfiguration mgrConf, JarExecutorConfiguration conf)
		throws MDTInstanceManagerException {
		Preconditions.checkArgument(conf != null, "JarExecutorConfiguration is null");
		Preconditions.checkArgument(conf.getWorkspaceDir() != null, "JarExecutorConfiguration.workspaceDir is null");

		m_mgrConfig = mgrConf;
		m_execConfig = conf;
		m_workspaceDir = conf.getWorkspaceDir();
		Try.accept(m_workspaceDir, FileUtils::createDirectory);

		m_startSemaphore = new Semaphore(conf.getStartConcurrency());
	}

	/**
	 * 작업 디렉터리를 반환한다. 인스턴스별 하위 디렉터리가 이 경로 아래에 생성된다.
	 *
	 * @return 작업 디렉터리 {@link File}.
	 */
	public File getWorkspaceDir() {
		return m_workspaceDir;
	}
	
	/**
	 * 주어진 식별자의 MDTInstance를 JAR 프로세스로 시작한다.
	 * <p>
	 * 동시 시작 제한 semaphore를 획득한 뒤 {@link #startWithSemaphore(String, String, JarExecutionArguments)}로
	 * 프로세스를 기동하고, 표준 출력에서 sentinel 문자열을 감시하여 시작 완료까지 대기한다.
	 * sentinel 감시 결과에 따라 인스턴스 상태를 RUNNING 또는 FAILED로 갱신하며, 상태 변화는
	 * 등록된 {@link JarExecutionListener}들에게 통보된다.
	 *
	 * @param id	MDTInstance 식별자.
	 * @param aasId	AssetAdministrationShell 식별자.
	 * @param args	JAR 실행 인자.
	 * @return 시작 후 상태와 endpoint를 담은 {@link Tuple}. 성공 시 RUNNING+endpoint, 실패 시 FAILED+{@code null}.
	 * @throws MDTInstanceExecutorException	semaphore 획득이 인터럽트되었거나 시작 과정에서 오류가 발생한 경우.
	 */
	public Tuple<MDTInstanceStatus,String> start(String id, String aasId, JarExecutionArguments args)
		throws MDTInstanceExecutorException {
		// 동시에 실행할 수 있는 프로세스 수 제한을 위해 semaphore를 획득한다.
		try {
			s_logger.debug("acquiring start-semaphore: thread={}", Thread.currentThread().getName());
			m_startSemaphore.acquire();
		}
		catch ( InterruptedException e ) {
			s_logger.warn("interrupted while acquiring start semaphore: thread={}", Thread.currentThread().getName());
			Thread.currentThread().interrupt();
			throw new MDTInstanceExecutorException("interrupted while acquiring start semaphore", e);
		}
		
		try {
			return startWithSemaphore(id, aasId, args);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			Throwables.throwIfInstanceOf(cause, MDTInstanceExecutorException.class);
			throw new MDTInstanceExecutorException("failed to start MDTInstance: id=" + id, cause);
		}
		finally {
			m_startSemaphore.release();
			s_logger.debug("released a start-semaphore: thread={}", Thread.currentThread().getName());
		}
	}
	
	private Tuple<MDTInstanceStatus,String> startWithSemaphore(String id, String aasId,
																JarExecutionArguments args)
		throws MDTInstanceExecutorException {
    	File instHomeDir = new File(m_workspaceDir, id);
		File logDir = new File(instHomeDir, "logs");
		
		// 혹시나 있을지 모를 'logs' 디렉토리 삭제.
    	Try.accept(logDir, FileUtils::deleteDirectory);

    	String argEncoding = "-Dfile.encoding=UTF-8";
    	String heapSize = Optionals.getOrElse(m_execConfig.getHeapSize(), DEFAULT_HEAP_SIZE);
    	String argInitialHeap = String.format("-Xms%s", heapSize);
    	String argMaxHeap = String.format("-Xmx%s", heapSize);
    	
    	String argId = String.format("--id=%s", id);
    	String argType = "--type=jar";
    	String argVerbose = "-v";
//    	String noValid = "--no-validation";
    	
    	File configFile = new File(instHomeDir, "config.json");
    	if ( !configFile.exists() ) {
    		try {
    			s_logger.info("creating an empty config file: {}", configFile);
				IOUtils.toFile("{}", configFile);
			}
			catch ( IOException e ) {
				throw new MDTInstanceExecutorException("failed to create a config file: " + configFile, e);
			}
    	}

    	List<String> argList = Lists.newArrayList("java", argEncoding, argInitialHeap, argMaxHeap,
    												"-jar", args.getJarFile(), argId, argType, argVerbose);

    	if ( args.getPort() > 0 ) {
			argList.add(String.format("--port=%d", args.getPort()));
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
		builder.directory(instHomeDir);
		
		Preconditions.checkState(m_mgrConfig.getMdtUrl() != null,
								"MDT URL is not configured in MDTInstanceManagerConfiguration");
		Map<String,String> initEnvVars = Map.of(
											"MDT_INSTANCE_ID", id,
											"MDT_INSTANCE_HOME", instHomeDir.getAbsolutePath(),
											"MDT_URL", m_mgrConfig.getMdtUrl());
		Map<String,String> udEnvVars = Maps.newHashMap(initEnvVars);
		
		// MDT Instance Manager에서 설정된 환경변수들을 설정
		// 환경 변수 파일에서 로드된 환경변수들은 실제로 MDTInstanceManager의 환경변수가
		// 아니기 때문에 명시적으로 추가해준다.
		KeyValueFStream.from(MDTManagerEnvironment.getVariables())
						.forEach(kv -> udEnvVars.put(kv.key(), String.valueOf(kv.value())));
		
		// 환경 변수 파일에서 환경변수들을 로드하여 설정
		try {
			File envFile = new File(instHomeDir, "env.file");
			KeyValueFStream.from(EnvironmentFileLoader.from(envFile)
														.withFacts(udEnvVars)
														.load())
							.forEach(kv -> udEnvVars.put(kv.key(), kv.value()));
		}
		catch ( FileNotFoundException ignored ) {
			// 무시함: 환경 변수 파일이 없는 경우에는 기본 환경 변수들만 사용한다.
		}
		catch ( Throwable e ) {
			s_logger.warn("failed to load variables from env.file", e);
			throw new MDTInstanceExecutorException("failed to load variables from env.file", e);
		}
		
		s_logger.info("creating MDTInstance: home={}, args={}, envs={}",
						m_workspaceDir.getAbsolutePath(), argList, udEnvVars);
		builder.environment().putAll(udEnvVars);

		File stdoutLogFile = new File(logDir, "output.log");
		builder.redirectErrorStream(true);
		builder.redirectOutput(Redirect.appendTo(stdoutLogFile));

		ProcessDesc procDesc = new ProcessDesc(id, null, MDTInstanceStatus.STARTING, stdoutLogFile);
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
			s_logger.warn("failed to start jar application: id={}, argList={}", id, argList, cause);
			m_guard.run(() -> {
				procDesc.m_status = MDTInstanceStatus.FAILED;
				procDesc.m_endpoint = null;
				notifyStatusChanged(procDesc);
			});
			
			s_logger.debug("releasing a start semaphore due to failure: thread={}",
							Thread.currentThread().getName());
			
			return Tuple.of(procDesc.m_status, procDesc.m_endpoint);
		}
		
		// 프로세스를 시작시킨 후, 출력 메시지를 검사하여 서비스 포트가 오픈될 때까지 대기한다.
		return waitWhileStarting(id, procDesc);
	}

    /**
     * 실행 중인 MDTInstance에 종료를 요청한다.
     * <p>
     * 대상 인스턴스의 상태가 {@link MDTInstanceStatus#RUNNING} 또는 {@link MDTInstanceStatus#STARTING}일 때만
     * 종료가 수행되며, 그 외 상태에서는 {@code null}을 반환한다. 종료 요청은 별도 스레드에서
     * 비동기적으로 처리되며 본 메서드는 STOPPING 상태로 전환한 뒤 즉시 반환한다.
     *
     * @param instanceId 종료할 MDTInstance 식별자.
     * @return 호출 직후 상태와 endpoint를 담은 {@link Tuple}, 또는 대상 인스턴스가 없거나 종료 불가능 상태이면 {@code null}.
     */
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

    /**
     * 주어진 식별자의 MDTInstance 현재 상태와 endpoint를 반환한다.
     * <p>
     * 본 실행기가 관리하지 않는 인스턴스는 {@code (STOPPED, null)}로 간주된다.
     *
     * @param instanceId 조회 대상 MDTInstance 식별자.
     * @return 상태와 endpoint를 담은 {@link Tuple}. 등록되지 않은 인스턴스는 {@code (STOPPED, null)}.
     */
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

	/**
	 * 상태 변화 이벤트를 받을 {@link JarExecutionListener}를 등록한다.
	 *
	 * @param listener 등록할 리스너.
	 * @return 새로 등록되었으면 {@code true}, 이미 등록되어 있었으면 {@code false}.
	 */
	public boolean addExecutionListener(JarExecutionListener listener) {
		return m_guard.get(() -> m_listeners.add(listener));
	}

	/**
	 * 등록된 {@link JarExecutionListener}를 해제한다.
	 *
	 * @param listener 해제할 리스너.
	 * @return 해제되었으면 {@code true}, 등록되어 있지 않았으면 {@code false}.
	 */
	public boolean removeExecutionListener(JarExecutionListener listener) {
		return m_guard.get(() -> m_listeners.remove(listener));
	}

	/**
	 * 주어진 식별자의 MDTInstance의 표준 출력 로그를 통째로 읽어 반환한다.
	 *
	 * @param id MDTInstance 식별자.
	 * @return 표준 출력 로그 전체 문자열.
	 * @throws IOException 로그 파일을 읽을 수 없는 경우.
	 */
	public String getOutputLog(String id) throws IOException {
		File logDir = new File(new File(m_workspaceDir, id), "logs");
		File stdoutLogFile = new File(logDir, "output.log");
		
		if ( !stdoutLogFile.canRead() ) {
			throw new IOException("Cannot read stdout log file: path=" + stdoutLogFile.getAbsolutePath());
		}
		
		return Files.readString(stdoutLogFile.toPath(), StandardCharsets.UTF_8);
	}
	
	/**
	 * 본 실행기를 종료한다.
	 * <p>
	 * 등록된 모든 인스턴스에 종료를 요청하고, 최대 5초간 종료 완료를 대기한다.
	 * 5초 안에 모두 종료되지 않으면 경고 로그를 남기고 반환한다 (잔여 프로세스는
	 * JVM 종료 시 OS에 정리됨).
	 */
	public void shutdown() {
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("Shutting down JarInstanceExecutor...");
		}

		StopWatch watch = StopWatch.start();
		int remains = m_guard.get(() -> {
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
					if ( s_logger.isWarnEnabled() ) {
						s_logger.warn("MDTInstances are still stopping: remains={}", remains);
					}
					break;
				}
				Thread.sleep(100);
				remains = m_guard.get(() -> m_runningInstances.size());
			}
			catch ( InterruptedException e ) {
				s_logger.warn("interrupted while waiting for MDTInstances to stop: remains={}", remains);
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	private static class ProcessDesc {
		private final String m_id;
		private Process m_process;
		private MDTInstanceStatus m_status;
		private String m_endpoint = null;
		private final File m_stdoutLogFile;
		
		public ProcessDesc(String id, Process process, MDTInstanceStatus status, File stdoutLogFile) {
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
			boolean[] shouldDestroy = { false };
			Tuple<MDTInstanceStatus,String> result = m_guard.get(() -> {
				if ( sentinel != null && sentinel.key() == 0 ) {
					// 'HTTP endpoint available on port' sentinel을 찾은 경우.
					// 프로세스가 성공적으로 시작되었다고 간주한다.
					//
					if ( s_logger.isDebugEnabled() ) {
						s_logger.debug("found sentinel: {}", sentinel.value());
					}

					// 만일 프로세스가 sentinel을 출력한 이후에 종료된 경우에는
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
			    	}
					procDesc.m_status = MDTInstanceStatus.FAILED;
					procDesc.m_endpoint = null;
					// destroyForcibly()는 guard 락 밖에서 호출 (락 보유 시간 단축).
					shouldDestroy[0] = true;
			    	notifyStatusChanged(procDesc);

					return procDesc.toResult();
				}
			});
			if ( shouldDestroy[0] ) {
				if ( s_logger.isInfoEnabled() ) {
					s_logger.info("kill fa3st-repository process: {}", procDesc.m_process.toHandle().pid());
				}
				procDesc.m_process.destroyForcibly();
			}
			return result;
		}
		catch ( Exception e ) {
			m_guard.run(() -> procDesc.m_status = MDTInstanceStatus.FAILED);
			procDesc.m_process.destroyForcibly();
			
	    	if ( s_logger.isInfoEnabled() ) {
	    		s_logger.info("failed to start an MDTInstance: {}", instId, e);
	    	}
	    	notifyStatusChanged(procDesc);

			return Tuple.of(MDTInstanceStatus.FAILED, null);
		}
	}
    
    @SuppressWarnings("unused")
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
		    		s_logger.info("failed to stop MDTInstance gracefully: id={}", instId, e);
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
	    		s_logger.info("failed MDTInstance: {}", procDesc.m_id, error);
	    	}
	    	notifyStatusChanged(procDesc);
		}
	}
	
	private void notifyStatusChanged(ProcessDesc pdesc) {
		Tuple<MDTInstanceStatus, String> result = pdesc.toResult();
    	for ( JarExecutionListener listener: m_listeners ) {
    		Unchecked.runOrIgnore(() -> listener.statusChanged(pdesc.m_id, result._1, result._2));
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
