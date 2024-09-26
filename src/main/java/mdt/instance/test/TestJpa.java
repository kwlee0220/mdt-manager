package mdt.instance.test;

import java.io.File;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonSerializer;

import com.google.common.collect.Maps;

import mdt.MDTConfiguration;
import mdt.MDTConfiguration.JdbcConfiguration;
import mdt.instance.jar.JarInstanceManager;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstance;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TestJpa {
	public static final void main(String... args) throws Exception {
		JsonSerializer ser = new JsonSerializer();
		
		Map<String,String> props = Maps.newHashMap();
		props.put("hibernate.show_sql", "true");
		props.put("hibernate.format_sql", "true");
		props.put("hibernate.hbm2ddl.auto", "create");
		
		JdbcConfiguration jdbcConfig = new JdbcConfiguration();
		jdbcConfig.setUrl("jdbc:h2:~/.mdt/descriptors");
		jdbcConfig.setUser("sa");
		jdbcConfig.setPassword("");
		
		MDTConfiguration conf = new TestMDTConfiguration();
		
		JarInstanceManager mgr = new JarInstanceManager(conf);
		
		MDTInstance inst;

		inst = mgr.addInstance("KR3", new File("aas_KR3.json"), "xx");
		inst = mgr.addInstance("CRF", new File("aas_CRF.json"), "xx");
		inst = mgr.addInstance("Cycle_냉매순환", new File("aas_Cycle_냉매순환.json"), "xx");
		inst = mgr.addInstance("Cycle_조립", new File("aas_Cycle_조립.json"), "xx");
		inst = mgr.addInstance("Door_조립", new File("aas_Door_조립.json"), "xx");
		inst = mgr.addInstance("KRCW-01EATT018", new File("aas_KRCW-01EATT018.json"), "xx");
		inst = mgr.addInstance("KRCW-01EATT019", new File("aas_KRCW-01EATT019.json"), "xx");
		inst = mgr.addInstance("KRCW-01ECEM001", new File("aas_KRCW-01ECEM001.json"), "xx");
		
		inst = mgr.getInstance("KR3");
		System.out.println(inst);
		inst = mgr.getInstance("CRF");
		System.out.println(inst);
		
		System.out.println("Cycles: --------------------------");
		for ( MDTInstance inst2: mgr.getAllInstancesByFilter("instance.id like '%Cycle%'") ) {
			System.out.println(inst2);
		}
		
		System.out.println("Process: --------------------------");
		for ( MDTInstance inst2: mgr.getAllInstancesByFilter("instance.assetType = 'Process'") ) {
			System.out.println(inst2);
		}
		
		System.out.println("Line: --------------------------");
		for ( MDTInstance inst2: mgr.getAllInstancesByFilter("instance.assetType = 'Line'") ) {
			System.out.println(inst2);
		}
		
		System.out.println("Machine: --------------------------");
		for ( MDTInstance inst2: mgr.getAllInstancesByFilter("instance.assetType = 'Machine'") ) {
			System.out.println(inst2);
		}
		
		System.out.println("01EATT: --------------------------");
		for ( MDTInstance inst2: mgr.getAllInstancesByFilter("instance.aasIdShort like '%01EATT%'") ) {
			System.out.println(inst2);
		}
		
		System.out.println("Data: --------------------------");
		for ( MDTInstance inst2: mgr.getAllInstancesByFilter("submodel.idShort = 'Data'") ) {
			System.out.println(inst2);
		}
		
		mgr.removeInstance("CRF");
		try {
			inst = mgr.getInstance("CRF");
			throw new AssertionError();
		}
		catch ( ResourceNotFoundException expected ) { }
	}
}
