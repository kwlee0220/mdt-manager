package mdt.instance;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import utils.func.FOption;

import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({"status", "baseEndpoint"})
public final class InstanceRuntimeInfo {
	private final MDTInstanceStatus m_status;
	private final @Nullable String m_baseEndpoint;
	
	public InstanceRuntimeInfo(@JsonProperty("status") MDTInstanceStatus status,
								@JsonProperty("baseEndpoint") @Nullable String baseEndpoint) {
		this.m_status = status;
		this.m_baseEndpoint = baseEndpoint;
	}
	
	public MDTInstanceStatus getStatus() {
		return m_status;
	}
	
	@Nullable
	public String getBaseEndpoint() {
		return m_baseEndpoint;
	}
	
	@Override
	public String toString() {
		String bepStr = FOption.getOrElse(m_baseEndpoint, "");
		return String.format("InstanceRuntimeInfo[status=%s, baseEndpoint=%s]", m_status, bepStr);
	}
}
