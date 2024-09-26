package mdt.instance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter
@ToString
@AllArgsConstructor
@JsonInclude(Include.NON_NULL)
public class InstanceStatusChangeEvent {
	private String id;
	private MDTInstanceStatus status;
	private String serviceEndpoint;

	public static InstanceStatusChangeEvent ADDED(String id) {
		return new InstanceStatusChangeEvent(id, MDTInstanceStatus.ADDED, null);
	}
	public static InstanceStatusChangeEvent STOPPED(String id) {
		return new InstanceStatusChangeEvent(id, MDTInstanceStatus.STOPPED, null);
	}
	public static InstanceStatusChangeEvent STARTING(String id) {
		return new InstanceStatusChangeEvent(id, MDTInstanceStatus.STARTING, null);
	}
	public static InstanceStatusChangeEvent RUNNING(String id, String svcEp) {
		return new InstanceStatusChangeEvent(id, MDTInstanceStatus.RUNNING, svcEp);
	}
	public static InstanceStatusChangeEvent STOPPING(String id) {
		return new InstanceStatusChangeEvent(id, MDTInstanceStatus.STOPPING, null);
	}
	public static InstanceStatusChangeEvent FAILED(String id) {
		return new InstanceStatusChangeEvent(id, MDTInstanceStatus.FAILED, null);
	}
	public static InstanceStatusChangeEvent REMOVED(String id) {
		return new InstanceStatusChangeEvent(id, MDTInstanceStatus.REMOVED, null);
	}
}
