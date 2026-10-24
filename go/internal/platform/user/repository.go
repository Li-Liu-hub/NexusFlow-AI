package user

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// ErrNotFound 表示按条件查不到记录（Java 侧返回 null 的那些分支）。
var ErrNotFound = errors.New("user: 记录不存在")

// Repository 是 a_user / a_auth_token 的仓储。
//
// 这里刻意保留裸 SQL 而不是上 ORM：现有 Java 实现本来就是 JdbcTemplate，
// 一句 SQL 对一句 SQL 才方便做新旧对照审查。
type Repository struct {
	db *sql.DB
}

// NewRepository 构造仓储。
func NewRepository(db *sql.DB) *Repository {
	return &Repository{db: db}
}

const userColumns = `id, user_code, user_name, password_hash, role, status,
	created_at, warehouse_code, warehouse_name`

// FindByCode 按 user_code 查账号。查不到返回 ErrNotFound。
func (r *Repository) FindByCode(ctx context.Context, userCode string) (*User, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT `+userColumns+` FROM a_user WHERE user_code = ?`, userCode)

	var (
		u       User
		created sql.NullTime
		wCode   sql.NullString
		wName   sql.NullString
	)
	err := row.Scan(&u.ID, &u.UserCode, &u.UserName, &u.PasswordHash, &u.Role, &u.Status,
		&created, &wCode, &wName)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("user: 查询账号失败: %w", err)
	}
	if created.Valid {
		t := created.Time
		u.CreatedAt = &t
	}
	if wCode.Valid {
		u.WarehouseCode = &wCode.String
	}
	if wName.Valid {
		u.WarehouseName = &wName.String
	}
	return &u, nil
}

// IssueToken 签发令牌：UUID 落库，ttl 后过期。
//
// 与 Java 的差异说明：Java 用 UUID.randomUUID().toString()，同样是 v4 小写。
// 令牌是不透明串（不是 JWT），校验靠查库——这样登出能真正吊销。
func (r *Repository) IssueToken(ctx context.Context, userCode, role string, ttl time.Duration) (string, error) {
	token := uuid.NewString()
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO a_auth_token (token, user_code, role, expires_at) VALUES (?, ?, ?, ?)`,
		token, userCode, role, time.Now().Add(ttl))
	if err != nil {
		return "", fmt.Errorf("user: 签发令牌失败: %w", err)
	}
	return token, nil
}

// FindToken 校验令牌：存在且未过期才算有效。
func (r *Repository) FindToken(ctx context.Context, token string) (TokenPrincipal, bool, error) {
	var p TokenPrincipal
	err := r.db.QueryRowContext(ctx,
		`SELECT user_code, role FROM a_auth_token WHERE token = ? AND expires_at > NOW()`,
		token).Scan(&p.UserCode, &p.Role)
	if errors.Is(err, sql.ErrNoRows) {
		return TokenPrincipal{}, false, nil
	}
	if err != nil {
		return TokenPrincipal{}, false, fmt.Errorf("user: 校验令牌失败: %w", err)
	}
	return p, true, nil
}

// DeleteToken 吊销令牌，返回受影响行数（登出时用来判断是否真删掉了）。
func (r *Repository) DeleteToken(ctx context.Context, token string) (int64, error) {
	res, err := r.db.ExecContext(ctx, `DELETE FROM a_auth_token WHERE token = ?`, token)
	if err != nil {
		return 0, fmt.Errorf("user: 吊销令牌失败: %w", err)
	}
	return res.RowsAffected()
}

// PurgeExpired 清理该账号的过期令牌。
// Java 侧不引定时任务，靠登录时顺手清——保持一致。
func (r *Repository) PurgeExpired(ctx context.Context, userCode string) error {
	_, err := r.db.ExecContext(ctx,
		`DELETE FROM a_auth_token WHERE user_code = ? AND expires_at <= NOW()`, userCode)
	if err != nil {
		return fmt.Errorf("user: 清理过期令牌失败: %w", err)
	}
	return nil
}
