package com.mt.wms.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class UserAdminService {
    private final UserAdminRepository repository;
    private final PasswordEncoder passwordEncoder;

    UserAdminService(UserAdminRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    List<UserAdminModels.UserView> list(WmsPrincipal principal) {
        return repository.findUsers(principal.companyId()).stream().map(this::view).toList();
    }

    List<UserAdminModels.RoleOption> roles() { return repository.roles(); }

    @Transactional
    UserAdminModels.UserView create(WmsPrincipal principal, UserAdminModels.SaveUserRequest request) {
        if (request.password() == null || request.password().length() < 8 || request.password().length() > 72) {
            throw new IllegalArgumentException("新建用户密码长度必须为8到72位");
        }
        try {
            long id = repository.create(principal.companyId(), request, passwordEncoder.encode(request.password()));
            repository.replaceAssignments(principal.companyId(), id, request);
            return view(repository.findUser(principal.companyId(), id).orElseThrow());
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("用户名或手机号已存在");
        }
    }

    @Transactional
    UserAdminModels.UserView update(WmsPrincipal principal, long userId, UserAdminModels.SaveUserRequest request) {
        if (userId == principal.userId()) {
            throw new IllegalArgumentException("当前登录账号请通过个人密码入口维护，不能在用户列表中修改");
        }
        if (request.password() != null && !request.password().isBlank()
                && (request.password().length() < 8 || request.password().length() > 72)) {
            throw new IllegalArgumentException("重置密码长度必须为8到72位");
        }
        try {
            if (repository.update(principal.companyId(), userId, request) != 1) throw new IllegalArgumentException("用户不存在");
            repository.replaceAssignments(principal.companyId(), userId, request);
            if (request.password() != null && !request.password().isBlank()) repository.updatePassword(principal.companyId(), userId, passwordEncoder.encode(request.password()));
            return view(repository.findUser(principal.companyId(), userId).orElseThrow());
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("手机号已被其他用户使用");
        }
    }

    private UserAdminModels.UserView view(UserAdminRepository.UserRow row) {
        return new UserAdminModels.UserView(row.id(), row.username(), row.mobile(), row.displayName(), row.status(),
                row.loginEnabled(), row.passwordChangeRequired(), repository.roles(row.id()),
                repository.warehouses(row.id()), repository.owners(row.id()));
    }
}
