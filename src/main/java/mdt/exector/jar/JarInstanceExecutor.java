package mdt.exector.jar;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import utils.KeyValue;
import utils.StopWatch;
import utils.UnitUtils;
import utils.async.Executions;
import utils.async.Guard;
import utils.func.FOption;
import utils.func.Try;
import utils.func.Tuple;
import utils.func.Unchecked;
import utils.io.LogTailer;
import utils.stream.FStream;

import mdt.MDTConfiguration.JarExecutorConfiguration;
import mdt.instance.InstanceStatusChangeEvent;
import mdt.model.instance.JarExecutionArguments;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
public class JarInstanceExecutor {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstanceExecutor.class);
	
	private final File m_workspaceDir;
	private final Duration m_sampleInterval;
	@Nullable private final Duration m_startTimeout;
	private final String m_heapSize;
	
	private final Guard m_guard = Guard.create();
	private final Map<String,ProcessDesc> m_runningInstances = Maps.newHashMap();
	private final Set<JarExecutionListener> m_listeners = Sets.newConcurrentHashSet();
	
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
						s_logger.warn("{} MDTInstances are still remains stopping : {}s", remains);
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
		private int m_repoPort = -1;
		private final File m_stdoutLogFile;
		
		public ProcessDesc(String id, Process process, MDTInstanceStatus status,
							String serviceEndpoint, File stdoutLogFile) {
			this.m_id = id;
			this.m_process = process;
			this.m_status = status;
			this.m_stdoutLogFile = stdoutLogFile;
		}
		
		public Tuple<MDTInstanceStatus,Integer> toResult() {
			return Tuple.of(m_status, m_repoPort);
		}
		
		@Override
		public String toString() {
			return String.format("Process(id=%s, proc=%d, status=%s, repo_port=%s)",
									m_id, m_process.toHandle().pid(), m_status, m_repoPort);
		}
	}
	
	public JarInstanceExecutor(JarExecutorConfiguration conf) throws MDTInstanceManagerException {
		Preconditions.checkNotNull(conf.getWorkspaceDir());
		
		m_workspaceDir = conf.getWorkspaceDir();
		m_sampleInterval = conf.getSampleInterval();
		m_startTimeout = conf.getStartTimeout();
		m_heapSize = conf.getHeapSize();
	}
	
	public Tuple<MDTInstanceStatus,Integer> start(String id, String aasId, JarExecutionArguments args)
		throws MDTInstanceExecutorException {
		return m_guard.get(() -> startInGuard(id, aasId, args));
	}
	
	private Tuple<MDTInstanceStatus,Integer> startInGuard(String id, String aasId, JarExecutionArguments args)
		throws MDTInstanceExecutorException {
		ProcessDesc desc = m_runningInstances.get(id);
    	if ( desc != null ) {
	    	switch ( desc.m_status ) {
	    		case RUNNING:
	    		case STARTING:
	    			return Tuple.of(desc.m_status, desc.m_repoPort);
	    		default: break;
	    	}
    	}
    	
    	File jobDir = new File(m_workspaceDir, id);
		File logDir = new File(jobDir, "logs");
		
		// 혹시나 있지 모를 'logs' 디렉토리 삭제.
    	Try.accept(FileUtils::deleteDirectory, logDir);
    	
    	String heapSize = FOption.getOrElse(m_heapSize, "512m");
    	String initialHeap = String.format("-Xms%s", heapSize);
    	String maxHeap = String.format("-Xmx%s", heapSize);
		ProcessBuilder builder = new ProcessBuilder("java",
													initialHeap, maxHeap,
													"-jar", args.getJarFile(),
													"-m", args.getModelFile(),
													"-c", args.getConfigFile(),
													"-v");
		builder.directory(jobDir);
		
		File stdoutLogFile = new File(logDir, id + "_stdout");
		builder.redirectOutput(Redirect.to(stdoutLogFile));
		builder.redirectError(new File(logDir, id + "_stderr"));

		ProcessDesc procDesc = new ProcessDesc(id, null, MDTInstanceStatus.STARTING, null, stdoutLogFile);
		m_runningInstances.put(id, procDesc);
		
		try {
			Files.createDirectories(logDir.toPath());
			notifyStatusChanged(procDesc);
			
			procDesc.m_process = builder.start();
			procDesc.m_process.onExit()
								.whenCompleteAsync((proc, error) -> onProcessTerminated(procDesc, error));
			Executions.runAsync(() -> pollingServicePort(id, procDesc));
			
			return Tuple.of(procDesc.m_status, procDesc.m_repoPort);
		}
		catch ( IOException e ) {
			procDesc.m_status = MDTInstanceStatus.FAILED;
			if ( s_logger.isWarnEnabled() ) {
				s_logger.warn("failed to start jar application: cause=" + e);
			}
			notifyStatusChanged(procDesc);
			
			return Tuple.of(procDesc.m_status, procDesc.m_repoPort);
		}
	}
	
	public Tuple<MDTInstanceStatus,Integer> waitWhileStarting(String id) throws InterruptedException {
		return m_guard.getOrThrow(() -> {
			while ( true ) {
				ProcessDesc procDesc = m_runningInstances.get(id);
				if ( procDesc == null ) {
					return Tuple.of(MDTInstanceStatus.STOPPING, -1);
				}
				switch ( procDesc.m_status ) {
					case RUNNING:
					case STOPPED:
					case STOPPING:
					case REMOVED:
						return Tuple.of(procDesc.m_status, procDesc.m_repoPort);
					default:
						m_guard.await();
				}
			}
		});
	}
	
	private void onProcessTerminated(ProcessDesc procDesc, Throwable error) {
		if ( error == null ) {
			// m_runningInstances에 등록되지 않은 process들은
			// 모두 성공적으로 종료된 것으로 간주한다.
			m_guard.run(() -> {
				procDesc.m_status = (procDesc.m_status == MDTInstanceStatus.STOPPING)
									? MDTInstanceStatus.STOPPED
									: MDTInstanceStatus.FAILED;
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

    public Tuple<MDTInstanceStatus,Integer> stop(final String instanceId) {
    	ProcessDesc procDesc = m_guard.get(() -> {
        	ProcessDesc desc = m_runningInstances.get(instanceId);
        	if ( desc != null ) {
        		switch ( desc.m_status ) {
        			case RUNNING:
        			case STARTING:
                    	if ( s_logger.isInfoEnabled() ) {
                    		s_logger.info("stopping MDTInstance: {}", instanceId);
                    	}
        				desc.m_repoPort = -1;
                		desc.m_status = MDTInstanceStatus.STOPPING;
                		notifyStatusChanged(desc);
                		
//            			Executions.runAsync(() -> waitWhileStopping(instanceId, desc));
//                		desc.m_process.destroy();
                		Executions.runAsync(() -> desc.m_process.destroy());
                		break;
                	default:
                		return null;
        		}
        	}
        	return desc;
    	});
    	
    	return (procDesc != null) ? procDesc.toResult() : null;
    }

    public Tuple<MDTInstanceStatus,Integer> getStatus(String instanceId) {
    	return m_guard.get(() -> {
    		ProcessDesc desc = m_runningInstances.get(instanceId);
        	if ( desc != null ) {
        		return desc.toResult();
        	}
        	else {
        		return Tuple.of(MDTInstanceStatus.STOPPED, -1);
        	}
    	});
    }

	public List<Tuple<MDTInstanceStatus,Integer>> getAllStatuses() throws MDTInstanceExecutorException {
    	return m_guard.get(() -> {
    		return FStream.from(m_runningInstances.values())
							.map(pdesc -> pdesc.toResult())
							.toList();
    	});
	}
	
	public boolean addExecutionListener(JarExecutionListener listener) {
		return m_guard.get(() -> m_listeners.add(listener));
	}
	public boolean removeExecutionListener(JarExecutionListener listener) {
		return m_guard.get(() -> m_listeners.remove(listener));
	}
	
	private Tuple<MDTInstanceStatus,Integer> pollingServicePort(final String instId, ProcessDesc procDesc) {
		Instant started = Instant.now();
		
		LogTailer tailer = LogTailer.builder()
									.file(procDesc.m_stdoutLogFile)
									.startAtBeginning(false)
									.sampleInterval(this.m_sampleInterval)
									.timeout(this.m_startTimeout)
									.build();
		
		List<String> sentinels = Arrays.asList("HTTP endpoint available on port", "ERROR");
		SentinelFinder finder = new SentinelFinder(sentinels);
		tailer.addLogTailerListener(finder);
		
		try {
			tailer.run();
			
			final KeyValue<Integer,String> sentinel = finder.getSentinel();
			return m_guard.get(() -> {
				switch ( sentinel.key() ) {
					case 0:
						String[] parts = sentinel.value().split(" ");
						procDesc.m_repoPort = Integer.parseInt(parts[parts.length-1]);
						procDesc.m_status = MDTInstanceStatus.RUNNING;
						
				    	if ( s_logger.isInfoEnabled() ) {
				    		long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
				    		String elapsedStr = UnitUtils.toSecondString(elapsedMillis);
				    		s_logger.info("started MDTInstance: id={}, port={}, elapsed={}",
				    						instId, procDesc.m_repoPort, elapsedStr);
				    	}
				    	m_guard.signalAll();
				    	notifyStatusChanged(procDesc);
				    	
						return procDesc.toResult();
					case 1:
				    	if ( s_logger.isInfoEnabled() ) {
				    		s_logger.info("failed to start an MDTInstance: {}", instId);
				    		s_logger.info("kill fa3st-repository process: {}",
				    						procDesc.m_process.toHandle().pid());
				    	}
						procDesc.m_status = MDTInstanceStatus.FAILED;
				    	// 프로세스가 수행 중인 상태이기 때문에 강제로 프로세스를 강제로 종료시킨다.
				    	procDesc.m_process.destroyForcibly();
				    	m_guard.signalAll();
				    	notifyStatusChanged(procDesc);
				    	
						return procDesc.toResult();
					default:
						throw new AssertionError();
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
	    	
			return Tuple.of(MDTInstanceStatus.FAILED, -1);
		}
	}
    
    private InstanceStatusChangeEvent waitWhileStopping(final String instId, ProcessDesc procDesc) {
		try {
			LogTailer tailer = LogTailer.builder()
										.file(procDesc.m_stdoutLogFile)
										.startAtBeginning(false)
										.sampleInterval(this.m_sampleInterval)
										.timeout(this.m_startTimeout)
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
	
	private void notifyStatusChanged(ProcessDesc pdesc) {
		Tuple<MDTInstanceStatus, Integer> result = pdesc.toResult();
    	for ( JarExecutionListener listener: m_listeners ) {
    		Unchecked.runOrIgnore(() -> listener.stausChanged(pdesc.m_id, result._1, result._2));
    	}
	}
}
