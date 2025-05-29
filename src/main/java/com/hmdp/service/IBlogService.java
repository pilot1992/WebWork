package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author flyfish
 * @since 2025-2-20
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);


    /**
     * 修改点赞数量（一个用户只能点赞一次）
     * @param id
     * @return
     */
    Result likeBlog(Long id);


    /**
     * 查询点赞时间top5用户
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);
}
