package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author flyfish
 * @since 2025-2-20
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();

        if(isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            save(follow);
        }else {
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", id));
        }
        return Result.ok();
    }


    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();

        // 查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();

        return Result.ok(count > 0 );
    }
}
