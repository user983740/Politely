package com.politeai.domain.transform.model;

import lombok.Value;

/**
 * Immutable value object holding the result of a text transformation.
 */
@Value
public class TransformResult {
    String transformedText;
}
