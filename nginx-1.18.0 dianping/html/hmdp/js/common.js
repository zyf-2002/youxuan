// let commonURL = "http://192.168.50.115:8081";
let commonURL = "/api";
// 设置后台服务地址
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 10000;

// 登录令牌是一串随机字符，用户身份存在服务端 Redis 里。
// 前端只负责存下来、每个请求带上；令牌过期或被登出后服务端返回 401。
function saveLoginToken(token) {
  if (token) {
    sessionStorage.setItem("token", token);
  }
}
function clearLoginToken() {
  sessionStorage.removeItem("token");
  sessionStorage.removeItem("userInfo");
}
function getToken() {
  return sessionStorage.getItem("token");
}
// 本地有没有令牌。只是个粗判——令牌过期后这个字符串不会自动消失，
// 所以「需要登录」的地方不要用它做最终决定，用下面的 requireLogin()。
function hasLoginToken() {
  return !!getToken();
}

/**
 * 提示一句，然后去登录页。
 * 留 600ms 是为了让人看清提示（Element 的 message 默认要显示 3 秒，
 * 原来的 200ms 只够闪一下）。
 */
function goLoginAfter(msg) {
  if (window.Vue && Vue.prototype.$message) {
    Vue.prototype.$message.error(msg);
  }
  setTimeout(function () {
    location.href = "/login.html";
  }, 600);
}

/**
 * 「需要登录」的地方统一走它：页面入口、点赞、关注、抢购……
 * <ul>
 *   <li>本地没有令牌　→ 提示「请先登录」→ 去登录页</li>
 *   <li>本地有但已失效 → 提示「登录已过期，请重新登录」→ 去登录页</li>
 *   <li>令牌有效　　　→ resolve，调用方继续做自己的事</li>
 * </ul>
 * reject 表示已经提示并准备跳转了，调用方直接 return 就行，别再弹别的东西。
 */
function requireLogin() {
  if (!getToken()) {
    goLoginAfter("请先登录");
    return Promise.reject("请先登录");
  }
  // 令牌有效性只有服务端知道，所以探一次。skipAuthRedirect 是避免
  // 和全局 401 处理重复提示、重复跳转。
  return axios.get("/user/me", {skipAuthRedirect: true}).catch(function () {
    goLoginAfter("登录已过期，请重新登录");
    return Promise.reject("登录已过期，请重新登录");
  });
}
// request拦截器，将用户token放入头中
axios.interceptors.request.use(
  config => {
    config.headers = config.headers || {};
    const token = getToken();
    if (token) {
      config.headers['authorization'] = token;
    }
    return config;
  },
  error => {
    console.log(error)
    return Promise.reject(error)
  }
)
axios.interceptors.response.use(function (response) {
  // 判断执行结果
  if (!response.data.success) {
    return Promise.reject(response.data.errorMsg)
  }
  return response.data;
}, function (error) {
  // 一般是服务端异常或者网络异常
  console.log(error)
  if(error.code === "ECONNABORTED" || (error.message && error.message.indexOf("timeout") >= 0)){
    return Promise.reject("网络超时，请稍后重试");
  }
  if(!error.response){
    return Promise.reject("网络异常，请检查连接");
  }
  if(error.response.status == 401){
    // 统一处理：清掉本地令牌，提示，然后去登录页。
    // requireLogin() 自己发的探测请求会跳过这里（它自己会提示和跳转），
    // 否则会重复提示、重复跳转。
    clearLoginToken();
    if(!(error.config && error.config.skipAuthRedirect)){
      goLoginAfter("登录已过期，请重新登录");
    }
    return Promise.reject("登录已过期，请重新登录");
  }
  return Promise.reject("服务器异常");
});
axios.defaults.paramsSerializer = function(params) {
  let p = "";
  Object.keys(params).forEach(k => {
    if(params[k]){
      p = p + "&" + k + "=" + params[k]
    }
  })
  return p;
}
const util = {
  commonURL,
  getUrlParam(name) {
    let reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    let r = window.location.search.substr(1).match(reg);
    if (r != null) {
      return decodeURI(r[2]);
    }
    return "";
  },
  formatPrice(val) {
    if (typeof val === 'string') {
      if (isNaN(val)) {
        return null;
      }
      // 价格转为整数
      const index = val.lastIndexOf(".");
      let p = "";
      if (index < 0) {
        // 无小数
        p = val + "00";
      } else if (index === p.length - 2) {
        // 1位小数
        p = val.replace("\.", "") + "0";
      } else {
        // 2位小数
        p = val.replace("\.", "")
      }
      return parseInt(p);
    } else if (typeof val === 'number') {
      if (!val) {
        return null;
      }
      const s = val + '';
      if (s.length === 0) {
        return "0.00";
      }
      if (s.length === 1) {
        return "0.0" + val;
      }
      if (s.length === 2) {
        return "0." + val;
      }
      const i = s.indexOf(".");
      if (i < 0) {
        return s.substring(0, s.length - 2) + "." + s.substring(s.length - 2)
      }
      const num = s.substring(0, i) + s.substring(i + 1);
      if (i === 1) {
        // 1位整数
        return "0.0" + num;
      }
      if (i === 2) {
        return "0." + num;
      }
      if (i > 2) {
        return num.substring(0, i - 2) + "." + num.substring(i - 2)
      }
    }
  }
}
