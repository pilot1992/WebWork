package com.hmdp.controller.FollowController;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author flyfish
 * @since 2025-2-20
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;


    /**
     *
     * @param id 关注的用户的id
     * @param isFollow 是否关注
     * @return
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable Long id, @PathVariable Boolean isFollow) {
        return followService.follow(id, isFollow);
    }


    /**
     *
     * @param id 关注的用户的id
     * @return
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(Long id) {
        return followService.isFollow(id);
    }
}
