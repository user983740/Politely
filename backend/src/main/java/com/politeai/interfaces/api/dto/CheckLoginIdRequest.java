package com.politeai.interfaces.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CheckLoginIdRequest(
        @NotBlank(message = "아이디를 입력해주세요")
        @Size(min = 3, max = 30, message = "아이디는 3자 이상 30자 이하로 입력해주세요")
        String loginId
) {}
