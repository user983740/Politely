package com.politeai.interfaces.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이메일을 입력해주세요")
        @Email(message = "올바른 이메일 형식이 아닙니다")
        String email,

        @NotBlank(message = "아이디를 입력해주세요")
        @Size(min = 3, max = 30, message = "아이디는 3자 이상 30자 이하로 입력해주세요")
        String loginId,

        @NotBlank(message = "이름을 입력해주세요")
        @Size(max = 50, message = "이름은 50자 이하로 입력해주세요")
        String name,

        @NotBlank(message = "비밀번호를 입력해주세요")
        @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=]).{8,}$",
                message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함하여 8자 이상이어야 합니다")
        String password,

        boolean privacyAgreed
) {}
