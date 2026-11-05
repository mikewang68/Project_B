package com.bdemo.iam.user;

import com.bdemo.iam.common.BizException;

/** Identifies a username collision without including credentials or the existing user's identity. */
public final class UsernameConflictException extends BizException {
  public UsernameConflictException() {
    super(409, "用户名已存在");
  }
}
