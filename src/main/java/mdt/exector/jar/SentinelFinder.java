package mdt.exector.jar;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;

import utils.KeyValue;
import utils.io.LogTailerListener;


/**
*
* @author Kang-Woo Lee (ETRI)
*/
public class SentinelFinder implements LogTailerListener {
	private final List<String> m_sentinels;
	
	@Nullable private KeyValue<Integer,String> m_sentinel;
	
	public SentinelFinder(List<String> sentinels) {
		m_sentinels = sentinels;
	}
	
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