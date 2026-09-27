Vue.component("footBar", {
  template: `
    <div class="foot">
    <div class="foot-box" :class="{active: activeBtn === 1}" @click="toPage(1)">
      <div class="foot-view"><i class="el-icon-s-home"></i></div>
      <div class="foot-text">首页</div>
    </div>
    <div class="foot-box" :class="{active: activeBtn === 2}" @click="toPage(2)">
      <div class="foot-view"><i class="el-icon-map-location"></i></div>
      <div class="foot-text">地图</div>
    </div>
    <div class="foot-box" @click="toPage(0)">
      <img class="add-btn" src="/imgs/add.png" alt="">
    </div>
    <div class="foot-box" :class="{active: activeBtn === 3}" @click="toPage(3)">
      <div class="foot-view"><i class="el-icon-magic-stick"></i></div>
      <div class="foot-text">助手</div>
    </div>
    <div class="foot-box" :class="{active: activeBtn === 4}" @click="toPage(4)">
      <div class="foot-view"><i class="el-icon-user"></i></div>
      <div class="foot-text">我的</div>
    </div>
  </div>
  `,
  data() {
    return {
    }
  },
  props: ['activeBtn'],
  methods: {
    toPage(i) {
      // 「发笔记 / 助手 / 我的」三个入口都要登录身份。
      // 这里先确认令牌真的有效再跳过去——本地那个字符串判断不出有效性，
      // 直接跳的话会出现「先进去闪一下、再被 401 弹回登录页」。
      // 其余入口（首页等）游客可以随便逛，不做检查。
      const target = {0: "/blog-edit.html", 3: "/agent.html", 4: "/info.html"}[i];
      if (target) {
        requireLogin().then(() => {
          location.href = target;
        }).catch(() => {
          // 没登录或已过期，requireLogin 已经提示并准备跳登录页了
        });
        return;
      }
      if (i === 1) {
        location.href = "/"
      }
    }
  }
})