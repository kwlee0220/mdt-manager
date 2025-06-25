package mdt.exector.jar;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;

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
	
	private @Nullable KeyValue<Integer,String> m_sentinel;
	
	/**
	 * Sentinel 감지기 객체를 생성한다.
	 * 
	 * @param sentinels    Sentinel 문자열 목록.
	 */
	public SentinelFinder(List<String> sentinels) {
		Preconditions.checkArgument(sentinels != null && sentinels.size() > 0, "sentinels is empty");
		
		m_sentinels = sentinels;
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
		return true;
	}

	@Override
	public boolean logFileRewinded(File file) {
		return true;
	}
}