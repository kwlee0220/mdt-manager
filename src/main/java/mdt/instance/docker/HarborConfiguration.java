package mdt.instance.docker;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@NoArgsConstructor
@Accessors(prefix = "m_")
@JsonInclude(Include.NON_NULL)
public class HarborConfiguration {
	private String m_host;
	private String m_endpoint;
	private String m_project;
	private String m_user;
	private String m_password;
}
