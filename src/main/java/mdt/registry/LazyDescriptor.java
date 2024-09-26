package mdt.registry;

import java.util.function.Function;

import utils.func.Lazy;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class LazyDescriptor<D> {
	private final Lazy<D> m_descriptor;
	private final String m_jsonDescriptor;
	private final Function<String,D> m_deser;
	
	public LazyDescriptor(D desc, String jsonDesc) {
		m_descriptor = Lazy.of(desc);
		m_jsonDescriptor = jsonDesc;
		m_deser = null;
	}
	
	public LazyDescriptor(String jsonDesc, Function<String,D> deser) {
		m_descriptor = Lazy.of(this::deserialize);
		m_jsonDescriptor = jsonDesc;
		m_deser = deser;
	}
	
	public String getJson() {
		return m_jsonDescriptor;
	}
	
	public D get() {
		return m_descriptor.get();
	}
	
	private D deserialize() {
		return m_deser.apply(m_jsonDescriptor);
	}
}
