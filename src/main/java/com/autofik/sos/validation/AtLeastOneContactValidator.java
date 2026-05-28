package com.autofik.sos.validation;

import com.autofik.sos.dto.request.CreateUserRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AtLeastOneContactValidator implements ConstraintValidator<AtLeastOneContact, CreateUserRequest> {

    @Override
    public boolean isValid(CreateUserRequest request, ConstraintValidatorContext context) {
        if (request == null) return true;
        String phone = request.phone();
        String email = request.email();
        return (phone != null && !phone.isBlank()) || (email != null && !email.isBlank());
    }
}
