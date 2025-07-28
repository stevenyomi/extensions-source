function waitForElm(selector) {
    return new Promise(resolve => {
        if (document.querySelector(selector)) {
            return resolve(document.querySelector(selector))
        }
        const observer = new MutationObserver(() => {
            if (document.querySelector(selector)) {
                observer.disconnect()
                resolve(document.querySelector(selector))
            }
        })
        observer.observe(document.body, {
            childList: true,
            subtree: true
        })
    })
}
let hasImgReady = false
let scrolledOnce = false
let scrolling = false
async function scroll(fromStart = false) {
    if (scrolling || !hasImgReady) return
    window.__interface__.log("scroll")
    scrolling = true
    const maxScroll = document.documentElement.scrollTop + 5000
    if (fromStart) window.scrollTo(0, 0)
    await new Promise((resolve, reject) => {
        try {
            let lastScroll = 0
            window.__interface__.log(`start scrolling from ${document.documentElement.scrollTop}`)
            const interval = setInterval(() => {
                window.scrollBy(0, 100)
                const scrollTop = document.documentElement.scrollTop
                if (scrollTop >= maxScroll || scrollTop === lastScroll) {
                    clearInterval(interval)
                    scrolling = false
                    scrolledOnce = true
                    window.__interface__.log(`ended scrolling at ${document.documentElement.scrollTop}`)
                    resolve()
                } else {
                    lastScroll = scrollTop
                }
            }, 200)
        } catch (err) {
            reject(err.toString())
        }
    })
}
function loadPic(pageIndex) {
    if (scrolling || !hasImgReady) return
    if (!scrolledOnce) {
        scroll()
        return
    }
    document.querySelector("#mangalist").dispatchEvent(new CustomEvent('scroll'))
    const page = pageIndex + 1
    window.__interface__.log(`loadPic(${page})`)
    const target = document.querySelector(`div.mh_comicpic[p="${page}"] img[src]`)
    const mh_loaderr = document.querySelector(`div.mh_comicpic[p="${page}"] .mh_loaderr`)
    if (target) {
        window.__interface__.log(`target.scrollIntoView()`)
        target.scrollIntoView()
    } else if (mh_loaderr?.style.display != 'none') {
        window.__interface__.log(`mh_loaderr.scrollIntoView()`)
        mh_loaderr.scrollIntoView()
        mh_loaderr.querySelector('.mh_btn')?.click()
    } else {
        const x = document.querySelector(`div.mh_comicpic[p="${page}"]`)
        window.__interface__.log(`else: ${x.innerHTML}`)
        window.__interface__.log(`scrollTop = ${document.documentElement.scrollTop}`)
        window.__interface__.log(JSON.stringify(x.getBoundingClientRect()))
        if (x.getBoundingClientRect().top < 0) {
            x.scrollIntoView()
            window.__interface__.log(`after scrollIntoView ${document.documentElement.scrollTop}`)
        }
//        x.scrollIntoView()
        scroll()
    }
}
let pageCount = 0
waitForElm("#mangalist").then(() => {
    pageCount = parseInt($.cookie(__cad.getCookieValue()[1] + mh_info.pageid) || "0")
    window.__interface__.setPageCount(pageCount)
})
const observer = new MutationObserver(() => {
    if (document.querySelector("div.mh_comicpic img")) {
        const images = document.querySelectorAll("div.mh_comicpic img")
        images.forEach(img => {
            if (!img._Hijacked) {
                const originalSrc = Object.getOwnPropertyDescriptor(img.__proto__, "src")
                Object.defineProperty(img, "src", {
                    ...originalSrc,
                    set: function (value) {
                        fetch(value).then(response => {
                            return response.blob()
                        }).then(blob => {
                            const reader = new FileReader()
                            reader.onloadend = () => {
                                window.__interface__.setPage(this.parentElement.getAttribute('p') - 1, reader.result)
                                hasImgReady = true
                            }
                            reader.readAsDataURL(blob)
                        })
                        originalSrc.set.call(this, value)
                    }
                })
                img._Hijacked = true
            }
        })
        if (pageCount>0 && images.length >= pageCount) {
            observer.disconnect()
        }
    }
})
observer.observe(document.body, { subtree: true, childList: true })
window.__interface__.log("JS load finished")
