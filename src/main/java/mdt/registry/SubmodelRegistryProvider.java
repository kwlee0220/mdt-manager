package mdt.registry;

import mdt.model.ResourceNotFoundException;
import mdt.model.registry.RegistryException;
import mdt.model.registry.SubmodelRegistry;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface SubmodelRegistryProvider extends SubmodelRegistry {
	public String getJsonSubmodelDescriptorById(String submodelId) throws ResourceNotFoundException,
																			RegistryException;
}
