package mdt;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.jdbc.JdbcConfiguration;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@NoArgsConstructor
@Accessors(prefix = "m_")
@JsonInclude(Include.NON_NULL)
public class JpaConfiguration {
	private JdbcConfiguration m_jdbc;
	private Map<String,String> m_properties;
}
