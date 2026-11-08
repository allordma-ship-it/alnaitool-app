// Package cache wraps Redis for token storage, caching, rate limiting and distributed locks.
package cache

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

// ErrMiss is returned when a key is absent.
var ErrMiss = errors.New("cache miss")

// Cache is a thin, typed facade over a Redis client.
type Cache struct {
	rdb *redis.Client
}

// Connect parses the URL, connects and pings Redis.
func Connect(ctx context.Context, url string) (*Cache, error) {
	opts, err := redis.ParseURL(url)
	if err != nil {
		return nil, fmt.Errorf("parse redis url: %w", err)
	}
	rdb := redis.NewClient(opts)
	for attempt := 1; attempt <= 10; attempt++ {
		if err = rdb.Ping(ctx).Err(); err == nil {
			return &Cache{rdb: rdb}, nil
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(time.Duration(attempt) * time.Second):
		}
	}
	return nil, fmt.Errorf("connect redis: %w", err)
}

// Close releases the client.
func (c *Cache) Close() error { return c.rdb.Close() }

// Ping checks Redis health.
func (c *Cache) Ping(ctx context.Context) error { return c.rdb.Ping(ctx).Err() }

// GetJSON decodes a cached JSON value into dst.
func (c *Cache) GetJSON(ctx context.Context, key string, dst any) error {
	b, err := c.rdb.Get(ctx, key).Bytes()
	if errors.Is(err, redis.Nil) {
		return ErrMiss
	}
	if err != nil {
		return err
	}
	return json.Unmarshal(b, dst)
}

// SetJSON stores a JSON-encoded value with a TTL.
func (c *Cache) SetJSON(ctx context.Context, key string, v any, ttl time.Duration) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return c.rdb.Set(ctx, key, b, ttl).Err()
}

// Delete removes keys.
func (c *Cache) Delete(ctx context.Context, keys ...string) error {
	return c.rdb.Del(ctx, keys...).Err()
}

// Bump increments a namespace generation counter, invalidating keys derived from it.
func (c *Cache) Bump(ctx context.Context, namespace string) error {
	return c.rdb.Incr(ctx, "gen:"+namespace).Err()
}

// Generation returns the current generation counter for a namespace.
func (c *Cache) Generation(ctx context.Context, namespace string) (int64, error) {
	n, err := c.rdb.Get(ctx, "gen:"+namespace).Int64()
	if errors.Is(err, redis.Nil) {
		return 0, nil
	}
	return n, err
}

// ---------------------------------------------------------------------------
// Refresh tokens
// ---------------------------------------------------------------------------

// StoreRefreshToken records a refresh token id belonging to a user.
func (c *Cache) StoreRefreshToken(ctx context.Context, tokenID, userID string, ttl time.Duration) error {
	pipe := c.rdb.TxPipeline()
	pipe.Set(ctx, "rt:"+tokenID, userID, ttl)
	pipe.SAdd(ctx, "rtu:"+userID, tokenID)
	pipe.Expire(ctx, "rtu:"+userID, ttl)
	_, err := pipe.Exec(ctx)
	return err
}

// ConsumeRefreshToken atomically deletes a refresh token and returns its owner (rotation).
func (c *Cache) ConsumeRefreshToken(ctx context.Context, tokenID string) (string, error) {
	userID, err := c.rdb.GetDel(ctx, "rt:"+tokenID).Result()
	if errors.Is(err, redis.Nil) {
		return "", ErrMiss
	}
	if err != nil {
		return "", err
	}
	c.rdb.SRem(ctx, "rtu:"+userID, tokenID)
	return userID, nil
}

// RevokeAllRefreshTokens deletes every refresh token of a user.
func (c *Cache) RevokeAllRefreshTokens(ctx context.Context, userID string) error {
	ids, err := c.rdb.SMembers(ctx, "rtu:"+userID).Result()
	if err != nil && !errors.Is(err, redis.Nil) {
		return err
	}
	keys := make([]string, 0, len(ids)+1)
	for _, id := range ids {
		keys = append(keys, "rt:"+id)
	}
	keys = append(keys, "rtu:"+userID)
	return c.rdb.Del(ctx, keys...).Err()
}

// ---------------------------------------------------------------------------
// Rate limiting
// ---------------------------------------------------------------------------

var slidingWindowScript = redis.NewScript(`
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
local count = redis.call('ZCARD', key)
if count >= limit then
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  return {0, count, tonumber(oldest[2]) + window - now}
end
redis.call('ZADD', key, now, member)
redis.call('PEXPIRE', key, window)
return {1, count + 1, 0}
`)

// RateLimitResult reports the outcome of a rate-limit check.
type RateLimitResult struct {
	Allowed    bool
	Count      int64
	RetryAfter time.Duration
}

// Allow applies a sliding-window limit of `limit` hits per `window` to key.
func (c *Cache) Allow(ctx context.Context, key string, limit int, window time.Duration) (RateLimitResult, error) {
	now := time.Now().UnixMilli()
	member := fmt.Sprintf("%d-%s", now, randomHex(6))
	res, err := slidingWindowScript.Run(ctx, c.rdb, []string{"rl:" + key}, now, window.Milliseconds(), limit, member).Int64Slice()
	if err != nil {
		return RateLimitResult{Allowed: true}, err
	}
	return RateLimitResult{
		Allowed:    res[0] == 1,
		Count:      res[1],
		RetryAfter: time.Duration(res[2]) * time.Millisecond,
	}, nil
}

// ---------------------------------------------------------------------------
// Distributed locks
// ---------------------------------------------------------------------------

var releaseScript = redis.NewScript(`
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
`)

// Lock is a held distributed lock.
type Lock struct {
	c     *Cache
	key   string
	token string
}

// TryLock acquires a lock if free; returns nil, nil when held by someone else.
func (c *Cache) TryLock(ctx context.Context, name string, ttl time.Duration) (*Lock, error) {
	token := randomHex(16)
	ok, err := c.rdb.SetNX(ctx, "lock:"+name, token, ttl).Result()
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, nil
	}
	return &Lock{c: c, key: "lock:" + name, token: token}, nil
}

// Release frees the lock only if still owned.
func (l *Lock) Release(ctx context.Context) error {
	return releaseScript.Run(ctx, l.c.rdb, []string{l.key}, l.token).Err()
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%x", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}
