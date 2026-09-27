package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    Result queryHotBlog(Integer current);

    /**
     * 查询某家店铺下的探店笔记，供店铺详情页展示
     *
     * @param shopId 店铺id
     */
    Result queryBlogByShopId(Long shopId);

    Result updateLike(Long id);

    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    Result quertBlogOfFollow(Long max, Integer offset);
}
