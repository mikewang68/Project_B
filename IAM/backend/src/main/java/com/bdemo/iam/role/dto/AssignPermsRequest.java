package com.bdemo.iam.role.dto;

import java.util.List;

public record AssignPermsRequest(List<String> permCodes) {
}
