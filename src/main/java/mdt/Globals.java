package mdt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.EventBus;


/**
 * 
 * @author Kang-Woo Lee (ETRI)
 */
public class Globals {
	static final Logger s_logger = LoggerFactory.getLogger(Globals.class);
	
	public static EventBus EVENT_BUS = new EventBus();
	
	private Globals() {
		throw new AssertionError("Should not be called: class=" + Globals.class);
	}
}
