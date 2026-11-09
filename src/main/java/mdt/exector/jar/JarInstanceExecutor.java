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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import utils.func.Try;
import utils.io.EnvironmentFileLoader;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.io.LogTailer;
import utils.stream.KeyValueFStream;
import utils.thread.Guard;

import mdt.controller.MDTManagerEnvironment;
import mdt.instance.MDTInstanceManagerConfiguration;
import mdt.instance.jar.JarExecutionArguments;
import mdt.instance.jar.JarExecutorConfiguration;
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
	/** mdt-instance가 KeyStore 암호를 읽는 환경 변수 이름({@code MDTInstanceMain.ENV_MDT_KEY_STORE_PASSWORD}과 같아야 한다). */
	static final String ENV_KEY_STORE_PASSWORD = "MDT_KEY_STORE_PASSWORD";
	/** 실행 중인 인스턴스 프로세스의 pid를 기록하는 파일 이름({@code <workspace>/<id>/}에 둔다). */
	static final String PID_FILE_NAME = "mdt-instance.pid";
	/** mdt-instance가 시작을 마치면 표준 출력에 찍는 마커({@code MDTInstanceMain}과 같아야 한다). */
	static final String STARTED_MARKER = "[***MARKER***]";

	private final MDTInstanceManagerConfiguration m_mgrConfig;
	private final JarExecutorConfiguration m_execConfig;
	private final File m_workspaceDir;
	private final Semaphore m_startSemaphore;	// 동시에 시작할 수 있는 프로세스 수 제한

	private final Guard m_guard = Guard.create();
	// 이 JarInstanceExecutor를 통해 실행 중인 모든 프로세스들의 등록정보
	private final Map<String,ProcessDesc> m_runningInstances = Maps.newHashMap();
	private final Set<JarExecutionListener> m_listeners = Sets.newConcurrentHashSet();
	/** 상태 변화 통지 전용 스레드(하나라서 통지 순서가 유지된다). {@link #notifyStatusChanged(ProcessDesc)} 참조. */
	private final ExecutorService m_notifier = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "jar-instance-status-notifier");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * 주어진 매니저/실행기 설정으로 {@link JarInstanceExecutor}를 생성한다.
	 * <p>
	 * 인스턴스는 매니저의 인스턴스 디렉터리({@link MDTInstanceManagerConfiguration#getInstancesDir()})의
	 * {@code <id>} 아래에서 실행한다. 매니저가 번들을 복사하는 곳과 같다. 디렉터리가 없으면 새로 만든다. 동시 시작
	 * 가능 프로세스 수는 {@link JarExecutorConfiguration#getStartConcurrency()} 값으로 제한된다.
	 * <p>
	 * 2026-11-05 이전에는 별도 설정 {@code executor.workspaceDir}(기본 {@code <cwd>/instances})에서 실행해, 두 설정이
	 * 다르면 모델·jar가 없는 디렉터리에서 기동했다(그 설정은 삭제).
	 *
	 * @param mgrConf	{@link MDTInstanceManagerConfiguration}. 인스턴스 디렉터리 / MDT URL / 전역 설정 파일 등을 제공.
	 * @param conf		{@link JarExecutorConfiguration}. 힙 크기/Key Store/제한 시간 등을 제공.
	 * @throws MDTInstanceManagerException	초기화 과정에서 오류가 발생한 경우.
	 * @throws IllegalArgumentException		{@code mgrConf} 또는 {@code conf}가 {@code null}인 경우.
	 */
	public JarInstanceExecutor(MDTInstanceManagerConfiguration mgrConf, JarExecutorConfiguration conf)
		throws MDTInstanceManagerException {
		Preconditions.checkArgument(mgrConf != null, "MDTInstanceManagerConfiguration is null");
		Preconditions.checkArgument(conf != null, "JarExecutorConfiguration is null");

		m_mgrConfig = mgrConf;
		m_execConfig = conf;
		m_workspaceDir = mgrConf.getInstancesDir();
		Try.accept(m_workspaceDir, FileUtils::createDirectory);

		m_startSemaphore = new Semaphore(conf.getStartConcurrency());
	}

	/**
	 * 작업 디렉터리(매니저의 인스턴스 디렉터리)를 반환한다. 인스턴스별 하위 디렉터리가 이 경로 아래에 있다.
	 *
	 * @return 작업 디렉터리 {@link File}.
	 */
	public File getWorkspaceDir() {
		return m_workspaceDir;
	}
	
	/**
	 * 주어진 식별자의 MDTInstance를 JAR 프로세스로 시작한다.
	 * <p>
	 * 동시 시작 제한 semaphore를 획득한 뒤 {@link #startWithSemaphore(ProcessDesc, String, JarExecutionArguments)}로
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
		// semaphore를 기다리기 전에 이 id의 실행 자리를 먼저 차지한다(STARTING). 같은 id로 두 번 시작하면 두 번째는
		// 여기서 거부된다. 2026-11-05 이전에는 semaphore를 얻은 뒤 put으로 등록해, 중복 시작한 앞의 프로세스가
		// 추적·중지할 수 없는 고아가 되었다.
		ProcessDesc procDesc = reserve(id);
		
		// 동시에 실행할 수 있는 프로세스 수 제한을 위해 semaphore를 획득한다.
		try {
			s_logger.debug("acquiring start-semaphore: thread={}", Thread.currentThread().getName());
			m_startSemaphore.acquire();
		}
		catch ( InterruptedException e ) {
			s_logger.warn("interrupted while acquiring start semaphore: thread={}", Thread.currentThread().getName());
			Thread.currentThread().interrupt();
			abandon(procDesc, MDTInstanceStatus.FAILED);
			throw new MDTInstanceExecutorException("interrupted while acquiring start semaphore", e);
		}
		
		try {
			return startWithSemaphore(procDesc, aasId, args);
		}
		catch ( Exception e ) {
			// 프로세스를 띄우기 전에 실패했으면(설정 파일, env 파일 등) 차지한 자리를 FAILED로 돌려놓는다.
			abandon(procDesc, MDTInstanceStatus.FAILED);
			Throwable cause = Throwables.unwrapThrowable(e);
			Throwables.throwIfInstanceOf(cause, MDTInstanceExecutorException.class);
			throw new MDTInstanceExecutorException("failed to start MDTInstance: id=" + id, cause);
		}
		finally {
			m_startSemaphore.release();
			s_logger.debug("released a start-semaphore: thread={}", Thread.currentThread().getName());
		}
	}
	
	// 이 id의 실행 자리를 차지한다(STARTING 등록·통지). 이미 실행 중(STARTING/RUNNING/STOPPING)이면 거부한다.
	private ProcessDesc reserve(String id) throws MDTInstanceExecutorException {
		File stdoutLogFile = new File(new File(new File(m_workspaceDir, id), "logs"), "output.log");
		ProcessDesc desc = new ProcessDesc(id, null, MDTInstanceStatus.STARTING, stdoutLogFile);
		ProcessDesc prev = m_guard.get(() -> {
			ProcessDesc existing = m_runningInstances.putIfAbsent(id, desc);
			if ( existing == null ) {
				notifyStatusChanged(desc);
			}
			return existing;
		});
		if ( prev != null ) {
			throw new MDTInstanceExecutorException("MDTInstance is already being executed: id=" + id
													+ ", status=" + prev.m_status);
		}
		return desc;
	}
	
	// 아직 프로세스를 띄우지 않은 실행 자리를 지우고 상태를 알린다(이미 프로세스가 있으면 아무것도 하지 않는다).
	private void abandon(ProcessDesc desc, MDTInstanceStatus status) {
		m_guard.run(() -> {
			if ( desc.m_process == null && m_runningInstances.get(desc.m_id) == desc ) {
				m_runningInstances.remove(desc.m_id);
				desc.m_status = status;
				desc.m_endpoint = null;
				notifyStatusChanged(desc);
			}
		});
	}
	
	private Tuple<MDTInstanceStatus,String> startWithSemaphore(ProcessDesc procDesc, String aasId,
																JarExecutionArguments args)
		throws MDTInstanceExecutorException {
		String id = procDesc.m_id;
    	File instHomeDir = new File(m_workspaceDir, id);
		File logDir = new File(instHomeDir, "logs");
		
		// 혹시나 있을지 모를 'logs' 디렉토리 삭제.
    	Try.accept(logDir, FileUtils::deleteDirectory);

    	String argEncoding = "-Dfile.encoding=UTF-8";
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

    	List<String> argList = Lists.newArrayList("java", argEncoding);
    	if ( m_execConfig.getHeapSize() != null ) {
        	String argInitialHeap = String.format("-Xms%s", m_execConfig.getHeapSize());
    		argList.add(argInitialHeap);
        	String argMaxHeap = String.format("-Xmx%s", m_execConfig.getHeapSize());
    		argList.add(argMaxHeap);
		}
    	argList.addAll(Arrays.asList("-jar", args.getJarFile(), argId, argType, argVerbose));

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
		
		// KeyStore 암호는 명령행(ps, /proc/<pid>/cmdline에 보임) 대신 환경 변수로 넘긴다. mdt-instance는
		// --keyStorePassword가 없으면 MDT_KEY_STORE_PASSWORD를 읽는다. 2026-11-05 이전에는 --keyStorePassword로 넘겼다.
		if ( m_execConfig.getKeyStorePassword() != null ) {
			udEnvVars.put(ENV_KEY_STORE_PASSWORD, m_execConfig.getKeyStorePassword());
		}
		
		// 환경 변수 값은 비밀 정보를 가려서 남긴다(2026-11-05 이전에는 평문).
		s_logger.info("creating MDTInstance: home={}, args={}, envs={}",
						m_workspaceDir.getAbsolutePath(), argList, MDTManagerEnvironment.maskVariables(udEnvVars));
		builder.environment().putAll(udEnvVars);

		builder.redirectErrorStream(true);
		builder.redirectOutput(Redirect.appendTo(procDesc.m_stdoutLogFile));

		// semaphore를 기다리는 동안 중지 요청을 받았으면 프로세스를 띄우지 않는다.
		if ( m_guard.get(() -> procDesc.m_status == MDTInstanceStatus.STOPPING) ) {
			s_logger.info("MDTInstance was stopped before launching its process: id={}", id);
			abandon(procDesc, MDTInstanceStatus.STOPPED);
			return Tuple.of(MDTInstanceStatus.STOPPED, null);
		}
		
		try {
			Files.createDirectories(logDir.toPath());
			
			Process instanceProcess = builder.start();
			instanceProcess.onExit()
							.whenCompleteAsync((proc, error) -> onProcessTerminated(procDesc, error));
			writePidFile(instHomeDir, instanceProcess.pid());
			// 프로세스를 띄우는 사이에 중지 요청을 받았으면 바로 종료시킨다. 2026-11-05 이전에는 이때 stop()이
			// null 프로세스를 destroy하다 NPE가 나서(CompletableFuture 안에서 사라짐) 상태가 STOPPING에 고정되었다.
			boolean stopRequested = m_guard.get(() -> {
				procDesc.m_process = instanceProcess;
				return procDesc.m_status == MDTInstanceStatus.STOPPING;
			});
			if ( stopRequested ) {
				terminateAsync(procDesc);
			}
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			s_logger.warn("failed to start jar application: id={}, argList={}", id, argList, cause);
			m_guard.run(() -> {
				m_runningInstances.remove(id);
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
						s_logger.debug("stopping MDTInstance: {}", instanceId);
                		desc.m_status = MDTInstanceStatus.STOPPING;
        				desc.m_endpoint = null;
                		notifyStatusChanged(desc);
                		
                		// 프로세스를 아직 띄우지 않았으면(semaphore 대기 중 등) 띄우는 쪽이 STOPPING을 보고 처리한다.
                		if ( desc.m_process != null ) {
                			terminateAsync(desc);
                		}
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
	 * 등록된 모든 인스턴스에 종료를 요청하고(정상 종료 요청 후 {@link JarExecutorConfiguration#getStopTimeout()}가
	 * 지나면 강제 종료), 그 시간보다 조금 더 기다린다. 그래도 남은 프로세스와 그 자손은 강제 종료한다.
	 * 2026-11-05 이전에는 5초 뒤 그냥 반환해, 남은 자식 프로세스가 매니저 종료 뒤에도 살아 있었다.
	 */
	public void shutdown() {
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("Shutting down JarInstanceExecutor...");
		}

		StopWatch watch = StopWatch.start();
		List<String> ids = m_guard.get(() -> List.copyOf(m_runningInstances.keySet()));
		for ( String id: ids ) {
			s_logger.info("shutting-down JarInstance: {}", id);
			stop(id);
		}
		
		long waitMillis = m_execConfig.getStopTimeout().toMillis() + 2000;
		int remains = m_guard.get(() -> m_runningInstances.size());
		try {
			while ( remains > 0 && watch.getElapsedInMillis() < waitMillis ) {
				Thread.sleep(100);
				remains = m_guard.get(() -> m_runningInstances.size());
			}
		}
		catch ( InterruptedException e ) {
			s_logger.warn("interrupted while waiting for MDTInstances to stop: remains={}", remains);
			Thread.currentThread().interrupt();
		}
		
		if ( remains > 0 ) {
			List<ProcessDesc> left = m_guard.get(() -> List.copyOf(m_runningInstances.values()));
			for ( ProcessDesc desc: left ) {
				Process proc = desc.m_process;
				if ( proc != null ) {
					s_logger.warn("killing MDTInstance still running at shutdown: id={}, pid={}", desc.m_id, proc.pid());
					proc.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
					proc.destroyForcibly();
				}
			}
		}
		// 남은 상태 통지(종료 알림 등)를 마저 보낸 뒤 통지 스레드를 끝낸다.
		m_notifier.shutdown();
		try {
			if ( !m_notifier.awaitTermination(5, TimeUnit.SECONDS) ) {
				m_notifier.shutdownNow();
			}
		}
		catch ( InterruptedException e ) {
			m_notifier.shutdownNow();
			Thread.currentThread().interrupt();
		}
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("elapsed in shutting down instances: {}s", watch.stopAndGetElpasedTimeString());
		}
	}
	
	/**
	 * 이전 실행에서 남은 인스턴스 프로세스를 찾아 종료한다.
	 * <p>
	 * 각 인스턴스 디렉터리의 pid 파일({@value #PID_FILE_NAME})을 읽어, 그 프로세스가 살아 있고 명령행에
	 * {@code --id=<인스턴스 id>}가 있으면(같은 pid를 다른 프로세스가 재사용한 경우를 거르기 위함) 종료한다.
	 * 매니저가 비정상 종료되어 정리를 못 한 경우를 위한 것이다. 이 기능이 생기기 전(2026-11-05)에 띄운 프로세스는
	 * pid 파일이 없어 찾지 못한다.
	 *
	 * @return	종료한 프로세스 수.
	 */
	public int killOrphanProcesses() {
		File[] instDirs = m_workspaceDir.listFiles(File::isDirectory);
		if ( instDirs == null ) {
			return 0;
		}
		
		int killed = 0;
		for ( File instDir: instDirs ) {
			File pidFile = new File(instDir, PID_FILE_NAME);
			if ( !pidFile.isFile() ) {
				continue;
			}
			String id = instDir.getName();
			try {
				long pid = Long.parseLong(Files.readString(pidFile.toPath()).trim());
				ProcessHandle proc = ProcessHandle.of(pid).orElse(null);
				if ( proc != null && proc.isAlive() && isInstanceProcess(proc, id) ) {
					s_logger.warn("stopping an orphan MDTInstance process: id={}, pid={}", id, pid);
					terminate(id, proc, m_execConfig.getStopTimeout());
					++killed;
				}
			}
			catch ( Exception e ) {
				s_logger.warn("failed to check an orphan MDTInstance process: id={}, cause={}", id, "" + e);
			}
			Try.run(() -> Files.deleteIfExists(pidFile.toPath()));
		}
		return killed;
	}
	
	private static boolean isInstanceProcess(ProcessHandle proc, String id) {
		String idArg = "--id=" + id;
		return proc.info().arguments()
						.map(args -> Arrays.asList(args).contains(idArg))
						.orElse(false);
	}
	
	private static void writePidFile(File instHomeDir, long pid) {
		try {
			Files.writeString(new File(instHomeDir, PID_FILE_NAME).toPath(), Long.toString(pid));
		}
		catch ( IOException e ) {
			s_logger.warn("failed to write pid file: dir={}, cause={}", instHomeDir, "" + e);
		}
	}
	
	// 프로세스 종료를 별도 스레드에서 수행한다. 오류는 로그로 남긴다(2026-11-05 이전에는 CompletableFuture 안에서 사라졌다).
	private void terminateAsync(ProcessDesc desc) {
		Process proc = desc.m_process;
		CompletableFuture.runAsync(() -> terminate(desc.m_id, proc.toHandle(), m_execConfig.getStopTimeout()))
						.exceptionally(e -> {
							s_logger.error("failed to stop MDTInstance process: id={}", desc.m_id, e);
							return null;
						});
	}
	
	/**
	 * 프로세스와 그 자손을 종료한다.
	 * <p>
	 * 먼저 정상 종료(SIGTERM)를 요청하고, {@code timeout} 안에 모두 끝나지 않으면 강제 종료(SIGKILL)한다. 자손
	 * 목록은 종료 요청 전에 만든다(부모가 끝나면 자손이 다른 부모 밑으로 옮겨져 {@code descendants()}에 나오지 않는다).
	 * 2026-11-05 이전에는 부모에게 {@code destroy()}를 한 번 보낼 뿐이었다.
	 *
	 * @return	정상 종료되었으면 {@code true}, 강제 종료했으면 {@code false}.
	 */
	static boolean terminate(String id, ProcessHandle proc, Duration timeout) {
		List<ProcessHandle> descendants = proc.descendants().toList();
		proc.destroy();
		descendants.forEach(ProcessHandle::destroy);
		
		if ( waitForExit(proc, descendants, timeout) ) {
			return true;
		}
		s_logger.warn("MDTInstance process did not stop within {}; killing it: id={}, pid={}", timeout, id, proc.pid());
		descendants.forEach(ProcessHandle::destroyForcibly);
		proc.destroyForcibly();
		return false;
	}
	
	private static boolean waitForExit(ProcessHandle proc, List<ProcessHandle> descendants, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		List<ProcessHandle> all = Lists.newArrayList(proc);
		all.addAll(descendants);
		for ( ProcessHandle handle: all ) {
			try {
				long remains = Math.max(0, deadline - System.nanoTime());
				handle.onExit().get(remains, TimeUnit.NANOSECONDS);
			}
			catch ( TimeoutException e ) {
				if ( handle.isAlive() ) {
					return false;
				}
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				return !handle.isAlive();
			}
			catch ( ExecutionException e ) { }
		}
		return true;
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
		
		// mdt-instance가 시작을 마치면 찍는 성공 마커만 찾는다. 시작에 실패한 mdt-instance는 종료하므로
		// (HomeDirPicocliCommand가 System.exit(-1)) 프로세스가 끝나면 곧바로 실패로 본다. 2026-11-05 이전에는
		// 부분 문자열 "ERROR"를 실패 마커로 써서, 라이브러리가 기동 중 찍는 무해한 ERROR 로그 하나로 시작 실패가
		// 되어 강제 종료되었고, 반대로 ERROR 없이 죽으면 startTimeout(기본 1분)까지 기다렸다.
		List<String> sentinels = Arrays.asList(STARTED_MARKER);
		SentinelFinder finder = new SentinelFinder(sentinels,
								() -> procDesc.m_process != null && !procDesc.m_process.isAlive());
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
				else if ( procDesc.m_status != MDTInstanceStatus.STARTING ) {
					// 그 사이 프로세스가 끝났거나(onProcessTerminated가 이미 상태를 알림) 중지 요청을 받았다.
					// 상태를 덮어쓰거나 다시 알리지 않는다(2026-11-05 이전에는 FAILED로 덮어쓰고 한 번 더 알렸다).
					return procDesc.toResult();
				}
				else {
					// 마커 없이 프로세스가 끝났거나(아직 종료 처리 전) 대기 시간이 지났다. 시작 실패로 본다.
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
					s_logger.info("kill the MDTInstance process that failed to start: id={}, pid={}",
									instId, procDesc.m_process.toHandle().pid());
				}
				procDesc.m_process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
				procDesc.m_process.destroyForcibly();
			}
			return result;
		}
		catch ( Exception e ) {
			// 시작 대기 시간이 지났거나 로그 감시가 실패했다. 그 사이 중지 요청을 받았거나(STOPPING) 프로세스가 이미
			// 끝났으면 상태를 덮어쓰지 않는다. 2026-11-05 이전에는 무조건 FAILED로 덮어써, 사용자가 시작 중에
			// 중지한 인스턴스도 FAILED로 남았다(검토 D43).
			boolean failed = m_guard.get(() -> {
				if ( procDesc.m_status != MDTInstanceStatus.STARTING ) {
					return false;
				}
				procDesc.m_status = MDTInstanceStatus.FAILED;
				procDesc.m_endpoint = null;
				notifyStatusChanged(procDesc);
				return true;
			});
			if ( failed ) {
		    	s_logger.info("failed to start an MDTInstance: {}", instId, e);
		    	// 항목은 프로세스가 끝날 때 onProcessTerminated가 지운다.
				procDesc.m_process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
				procDesc.m_process.destroyForcibly();
			}
			return m_guard.get(procDesc::toResult);
		}
	}
    
	
	private void onProcessTerminated(ProcessDesc procDesc, Throwable error) {
		Try.run(() -> Files.deleteIfExists(new File(new File(m_workspaceDir, procDesc.m_id), PID_FILE_NAME).toPath()));
		if ( error == null ) {
			// m_runningInstances에 등록되지 않은 process들은
			// 모두 성공적으로 종료된 것으로 간주한다.
			// 이미 FAILED로 알린 경우(시작 실패로 강제 종료)에는 다시 알리지 않는다.
			boolean changed = m_guard.get(() -> {
				MDTInstanceStatus prev = procDesc.m_status;
				procDesc.m_status = (prev == MDTInstanceStatus.STOPPING)
									? MDTInstanceStatus.STOPPED
									: MDTInstanceStatus.FAILED;
				procDesc.m_endpoint = null;
				m_runningInstances.remove(procDesc.m_id);
				if ( prev != MDTInstanceStatus.FAILED ) {
					notifyStatusChanged(procDesc);
					return true;
				}
				return false;
			});
	    	if ( changed && s_logger.isInfoEnabled() ) {
	    		s_logger.info("terminated MDTInstance: id={}, status={}", procDesc.m_id, procDesc.m_status);
	    	}
		}
		else {
			m_guard.run(() -> procDesc.m_status = MDTInstanceStatus.FAILED);
	    	if ( s_logger.isInfoEnabled() ) {
	    		s_logger.info("failed MDTInstance: {}", procDesc.m_id, error);
	    	}
	    	notifyStatusChanged(procDesc);
		}
	}
	
	/**
	 * 상태 변화를 리스너에게 알린다.
	 * <p>
	 * 호출 시점의 상태를 스냅샷으로 떠서 통지 전용 스레드({@link #m_notifier}, 하나라서 순서 유지)에 넘기고 바로
	 * 돌아온다. 그래서 {@link #m_guard} 락 안에서 불러도 리스너(JPA로 DB 갱신, MQTT 이벤트 발행)를 기다리지 않는다.
	 * 리스너 예외는 WARN으로 남긴다. 2026-11-05 이전에는 락을 쥔 채 리스너를 직접 불러, DB가 느리면 getStatus/stop이
	 * 모두 막혔고 리스너 예외는 흔적 없이 사라졌다.
	 */
	private void notifyStatusChanged(ProcessDesc pdesc) {
		String id = pdesc.m_id;
		Tuple<MDTInstanceStatus, String> result = pdesc.toResult();
		try {
			m_notifier.execute(() -> {
		    	for ( JarExecutionListener listener: m_listeners ) {
		    		try {
		    			listener.statusChanged(id, result._1, result._2);
		    		}
		    		catch ( Exception e ) {
		    			s_logger.warn("JarExecutionListener failed: id={}, status={}", id, result._1, e);
		    		}
		    	}
			});
		}
		catch ( RejectedExecutionException e ) {
			s_logger.warn("drop a status change after shutdown: id={}, status={}", id, result._1);
		}
	}
}
