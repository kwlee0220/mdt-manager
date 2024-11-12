package mdt.registry;

import mdt.aas.SubmodelRegistry;
import mdt.model.ResourceNotFoundException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface SubmodelRegistryProvider extends SubmodelRegistry {
	public String getJsonSubmodelDescriptorById(String submodelId) throws ResourceNotFoundException;
}
