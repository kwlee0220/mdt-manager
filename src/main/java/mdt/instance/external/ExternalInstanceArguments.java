package mdt.instance.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalInstanceArguments {
	@JsonProperty("lastModified") private long m_lastModified = -1;
	
	public long getLastModified() {
		return m_lastModified;
	}
	
	public void setLastModified(long lastModified) {
		m_lastModified = lastModified;
	}
	
	@Override
	public String toString() {
		return String.format("");
	}
	
	@Override
	public boolean equals(Object obj) {
		if ( this == obj ) {
			return true;
		}
		else if ( obj == null || getClass() != obj.getClass() ) {
			return false;
		}
		
		@SuppressWarnings("unused")
		ExternalInstanceArguments other = (ExternalInstanceArguments)obj;
		return false;
	}
}
