package mdt.repository;

import org.springframework.stereotype.Component;

import mdt.instance.jpa.JpaInstanceDescriptorRepository;
import mdt.instance.jpa.JpaMDTOperationDescriptorRepository;
import mdt.instance.jpa.JpaMDTParameterDescriptorRepository;
import mdt.instance.jpa.JpaMDTSubmodelDescriptorRepository;

import jakarta.persistence.EntityManagerFactory;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Component
public record Repositories(
	JpaInstanceDescriptorRepository instances,
	JpaMDTSubmodelDescriptorRepository submodels,
	JpaMDTParameterDescriptorRepository parameters,
	JpaMDTOperationDescriptorRepository operations,
	EntityManagerFactory entityManagerFactory
) { }