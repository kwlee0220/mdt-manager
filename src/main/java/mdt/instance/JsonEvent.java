package mdt.instance;

import lombok.AllArgsConstructor;
import lombok.Getter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter
@AllArgsConstructor
public class JsonEvent<T> {
	private String type;
	private T event;
	
	public JsonEvent(T event) {
		this(event.getClass().getTypeName(), event);
	}
}
