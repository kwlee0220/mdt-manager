package mdt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.jar.JarInstanceManager;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SpringBootApplication
@ConfigurationPropertiesScan("mdt")
public class MDTInstanceApplication {
	public static void main(String[] args) throws Exception {
        var context = SpringApplication.run(MDTInstanceApplication.class, args);
        
        var bean = context.getBean(AbstractJpaInstanceManager.class);
        if ( bean instanceof JarInstanceManager instMgr ) {
        	if ( instMgr.getConfiguration().isAutoStart() ) {
        		instMgr.startInstanceAll();
        	}
        }
	}
}
