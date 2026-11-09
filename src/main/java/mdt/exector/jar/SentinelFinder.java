package mdt.exector.jar;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeoutException;

import com.google.common.base.Preconditions;

import org.jetbrains.annotations.Nullable;

import utils.KeyValue;
import utils.io.LogTailerListener;


/**
 * 주어진 Sentinel 문자열을 감지하는 {@link LogTailerListener} 구현체.
 * <p>
 * 주어진 Sentinel 문자열 리스트 중 하나라도 발견되면, 그 문자열의 인덱스 번호를
 * 알려주는 작업을 수행한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SentinelFinder implements LogTailerListener {
	private final List<String> m_sentinels;
	private final @Nullable BooleanSupplier m_abortCondition;
	
	private @Nullable KeyValue<Integer,String> m_sentinel;
	private volatile boolean m_aborted = false;
	
	/**
	 * Sentinel 감지기 객체를 생성한다.
	 * 
	 * @param sentinels    Sentinel 문자열 목록.
	 */
	public SentinelFinder(List<String> sentinels) {
		this(sentinels, null);
	}
	
	/**
	 * 중단 조건이 있는 Sentinel 감지기 객체를 생성한다.
	 * <p>
	 * 로그 파일에 변화가 없을 때마다 {@code abortCondition}을 확인해, {@code true}이면 sentinel을 찾지 못한 채
	 * 감시를 끝낸다({@link #isAborted()}). 감시 대상 프로세스·컨테이너가 끝났는지 확인하는 데 쓴다.
	 * 2026-11-05 이전에는 이 조건이 없어, 프로세스가 죽어도 제한 시간까지 기다렸다.
	 *
	 * @param sentinels			Sentinel 문자열 목록.
	 * @param abortCondition	중단 조건. {@code null}이면 중단하지 않는다.
	 */
	public SentinelFinder(List<String> sentinels, @Nullable BooleanSupplier abortCondition) {
		Preconditions.checkArgument(sentinels != null && sentinels.size() > 0, "sentinels is empty");
		
		m_sentinels = sentinels;
		m_abortCondition = abortCondition;
	}
	
	/**
	 * 중단 조건 때문에 sentinel을 찾지 못하고 감시를 끝냈는지 반환한다.
	 *
	 * @return	중단되었으면 {@code true}.
	 */
	public boolean isAborted() {
		return m_aborted;
	}
	
	/**
	 * 감지된 sentinel 문자열과 해당 인덱스 번호를 반환한다.
	 * 
	 * @return	감지된 sentinel 문자열과 해당 인덱스 번호. 감지된 sentinel 문자열이 없는 경우는
	 *             {@code null}을 반환한다.
	 */
	public @Nullable KeyValue<Integer,String> getSentinel() {
		return m_sentinel;
	}

	@Override
	public boolean handleLogTail(String line) {
		for ( int i =0; i < m_sentinels.size(); ++i ) {
			if ( line.contains(m_sentinels.get(i)) ) {
				m_sentinel = KeyValue.of(i, line);
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean handleLogFileSilence(Duration interval) throws TimeoutException {
		if ( m_abortCondition != null && m_abortCondition.getAsBoolean() ) {
			m_aborted = true;
			return false;
		}
		return true;
	}

	@Override
	public boolean logFileRewinded(File file) {
		return true;
	}
}