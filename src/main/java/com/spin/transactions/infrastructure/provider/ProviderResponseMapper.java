package com.spin.transactions.infrastructure.provider;

import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.domain.RejectionReason;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.ValueMapping;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ProviderResponseMapper {

	String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
	String PROVIDER_REJECTED = "PROVIDER_REJECTED";

	ProviderResult.Approved toApproved(ProviderApprovalResponse response);

	@Mapping(target = "reason", source = "code")
	ProviderResult.Rejected toRejected(ProviderRejectionResponse response);

	@ValueMapping(source = INSUFFICIENT_FUNDS, target = INSUFFICIENT_FUNDS)
	@ValueMapping(source = MappingConstants.ANY_UNMAPPED, target = PROVIDER_REJECTED)
	@ValueMapping(source = MappingConstants.NULL, target = PROVIDER_REJECTED)
	RejectionReason toRejectionReason(String code);

}
