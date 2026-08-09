redis.call('DEL', KEYS[2])
redis.call('DEL', KEYS[1])

for index = 4, #ARGV, 2 do
    redis.call('ZADD', KEYS[1], ARGV[index], ARGV[index + 1])
end

if #ARGV > 3 then
    redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
end

redis.call('SET', KEYS[2], ARGV[3], 'PX', tonumber(ARGV[1]))

return 1
