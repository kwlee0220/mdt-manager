package mdt.instance.jar;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Tuple;

import mdt.exector.jar.JarInstanceExecutor;
import mdt.model.instance.MDTInstanceStatus;
import mdt.instance.JpaInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.MDTInstanceManagerException;


/**
 * MDTInstance를 별도의 JVM 프로세스(JAR 실행)로 구동하는 {@link JpaInstance} 구현체이다.
 * <p>
 * 본 클래스는 인스턴스 작업 디렉터리 관리와 {@link JarInstanceExecutor}를 통한 JAR 프로세스의
 * 시작/중지/출력 수집을 담당한다. 실행 인자는 JPA {@link JpaInstanceDescriptor}에 보관된
 * 인자 문자열을 매니저가 파싱하여 사용한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JarInstance extends JpaInstance {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstance.class);

	/**
	 * 주어진 매니저와 JPA 디스크립터로 {@link JarInstance}를 생성한다.
	 * <p>
	 * {@code protected} 생성자로 {@link JarInstanceManager} 또는 서브클래스에 의해서만 생성된다.
	 *
	 * @param manager	본 인스턴스가 속한 {@link JarInstanceManager}.
	 * @param desc		JPA 저장소에서 조회한 {@link JpaInstanceDescriptor}.
	 */
	protected JarInstance(JarInstanceManager manager, JpaInstanceDescriptor desc) {
		super(manager, desc);

		setLogger(s_logger);
	}

	/**
	 * 본 인스턴스의 작업 디렉터리를 반환한다.
	 *
	 * @return 매니저가 본 인스턴스에 할당한 홈 디렉터리 {@link File}.
	 */
	public File getHomeDir() {
		return getInstanceManager().getInstanceHomeDir(getId());
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * JPA 디스크립터에서 실행 인자를 가져와 파싱한 뒤, {@link JarInstanceExecutor}를 통해
	 * 비동기적으로 JAR 프로세스를 기동한다. 실행 도중 발생하는
	 * 예외는 로그로 남기고, 실행기가 상태를 알리기 전에 실패했으면 상태를 실제 상태(실행기가 모르면 FAILED)로
	 * 맞춘다({@code start()}가 STOPPED에서 기다리지 않도록).
	 *
	 * @throws MDTInstanceManagerException 실행 인자 파싱 등 준비 단계에서 오류가 발생한 경우.
	 */
	@Override
	public void startAsync() throws MDTInstanceManagerException {
		JarInstanceManager mgr = getInstanceManager();
		JarExecutionArguments jargs = mgr.parseExecutionArguments(getExecutionArguments());

		JarInstanceExecutor executor = mgr.getInstanceExecutor();
		getLogger().info("starting: {}...", this);
		
		String threadName = String.format("starting-JarInstance-%s", getId());
		new Thread(() -> {
			try {
				executor.start(getId(), getAasId(), jargs);
			}
			catch ( Exception e ) {
				getLogger().error("Failed to start instance: {}", this, e);
				syncStatusAfterStartFailure(executor);
			}
		}, threadName).start();
	}

	// 실행기가 상태를 알리기 전에 시작이 실패했으면(예: 실행기가 이미 이 id를 실행 중이라 거부) DB 상태가
	// STOPPED에 남아 start()가 기다리게 된다. 실행기가 아는 실제 상태로 맞추고, 실행기도 모르면 FAILED로 둔다.
	// 2026-11-05 이전에는 로그만 남겼다(검토 D13).
	private void syncStatusAfterStartFailure(JarInstanceExecutor executor) {
		try {
			Tuple<MDTInstanceStatus,String> actual = executor.getStatus(getId());
			if ( actual._1 != MDTInstanceStatus.STOPPED ) {
				getInstanceManager().transitionStatus(getId(), actual._1, actual._2);
			}
			else if ( reloadInstanceDescriptor().getStatus() == MDTInstanceStatus.STARTING
					|| reloadInstanceDescriptor().getStatus() == MDTInstanceStatus.STOPPED ) {
				// start()가 STARTING을 미리 차지했으므로(D36) STARTING에 남은 경우도 실패로 바꾼다.
				getInstanceManager().transitionStatus(getId(), MDTInstanceStatus.FAILED, null);
			}
		}
		catch ( Exception e ) {
			getLogger().warn("failed to update the status after a start failure: id={}, cause={}", getId(), "" + e);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * {@link JarInstanceExecutor}에 종료를 요청하고 바로 반환한다.
	 */
	@Override
	public void stopAsync() {
		getExecutor().stop(getId());
	}

	@Override
	public JarInstanceManager getInstanceManager() {
		return (JarInstanceManager)m_manager;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * {@link JarInstanceExecutor}로부터 본 인스턴스의 표준 출력 로그를 조회하여 반환한다.
	 */
	@Override
	public String getOutputLog() throws IOException {
		return getExecutor().getOutputLog(getId());
	}

	@Override
	public String toString() {
		return String.format("%s, path=%s", super.toString(), getHomeDir().getAbsolutePath());
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * 본 인스턴스의 작업 디렉터리({@link #getHomeDir()})를 재귀적으로 삭제한다.
	 * 삭제 실패 시 경고 로그를 남기고 {@link MDTInstanceManagerException}을 던진다.
	 */
	@Override
	protected void uninitialize() {
		// Instance용 디렉토리를 제거한다.
		File instanceDir = getHomeDir();

		try {
			FileUtils.deleteDirectory(instanceDir);
		}
		catch ( IOException e ) {
			getLogger().warn("Failed to delete MDTInstance workspace: dir={}", instanceDir, e);
			throw new MDTInstanceManagerException("Failed to delete instance directory: dir="
												+ instanceDir, e);
		}
	}

	/**
	 * 매니저로부터 본 인스턴스가 사용하는 {@link JarInstanceExecutor}를 반환하는 헬퍼.
	 */
	private JarInstanceExecutor getExecutor() {
		return getInstanceManager().getInstanceExecutor();
	}
}