package net.wicp.tams.duckula.task;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;

import com.alibaba.fastjson.JSONObject;

import net.wicp.tams.common.Conf;
import net.wicp.tams.common.apiext.CollectionUtil;
import net.wicp.tams.common.apiext.LoggerUtil;
import net.wicp.tams.common.apiext.StringUtil;
import net.wicp.tams.common.beans.Host;
import net.wicp.tams.common.constant.JvmStatus;
import net.wicp.tams.common.metrics.utility.TsLogger;
import net.wicp.tams.duckula.common.ConfUtil;
import net.wicp.tams.duckula.common.ZkClient;
import net.wicp.tams.duckula.common.ZkUtil;
import net.wicp.tams.duckula.common.beans.ColHis;
import net.wicp.tams.duckula.common.beans.Count;
import net.wicp.tams.duckula.common.constant.CommandType;
import net.wicp.tams.duckula.common.constant.ZkPath;
import net.wicp.tams.duckula.task.bean.DuckulaContext;
import net.wicp.tams.duckula.task.conf.ITaskConf;
import net.wicp.tams.duckula.task.conf.ZookeeperImpl;
import net.wicp.tams.duckula.task.disruptor.DisruptorProducer;
import net.wicp.tams.duckula.task.jmx.BinlogControl;
import net.wicp.tams.duckula.task.parser.IProducer;
import net.wicp.tams.duckula.task.parser.ParseLogOnline;

public class Main {
	static {
		System.setProperty("Log4jContextSelector", "org.apache.logging.log4j.core.async.AsyncLoggerContextSelector");
	}
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Main.class);
	public final static DuckulaContext context = new DuckulaContext();
	public static DuckulaGroup metric;

	public static void main(String[] args) {
		Thread.currentThread().setName("Duckula-main");
		if (ArrayUtils.isEmpty(args)) {
			System.err.println("----未传入taskid，不能启动task----");
			log.error("----未传入taskid，不能启动task----");
			return;
		}
		final String taskId = args[0];
		log.info("----------------------加载配置文件-------------------------------------");
		// final File confDir = IOUtil.getCurFolder(Main.class);
		CommandType.task.setCommonProps();

		log.info("----------------------得到分布式锁-------------------------------------");
		InterProcessMutex lock = null;
		try {
			lock = ZkUtil.lockTaskPath(taskId);
			if (!lock.acquire(30, TimeUnit.SECONDS)) {// 只等半分钟就好了
				List<String> ips = ZkClient.getInst().lockValueList(lock);
				log.error("已有服务[{}]在运行中,无法获得锁.", CollectionUtil.listJoin(ips, ","));
				LoggerUtil.exit(JvmStatus.s9);
			}
		} catch (Exception e1) {
			log.error("获取锁异常", e1);
			LoggerUtil.exit(JvmStatus.s9);
		}
		if (lock == null) {
			log.error("未获得分布式锁");
			LoggerUtil.exit(JvmStatus.s9);
		}

		System.setProperty(TsLogger.ENV_FILE_NAME, "tams_" + taskId);
		System.setProperty(TsLogger.ENV_FILE_ROOT, String.format("%s/logs/metrics", System.getenv("DUCKULA_DATA")));

		metric = new DuckulaGroup(taskId);

		log.info("----------------------执行的服务器信息-------------------------------------");
		try {
			InetAddress address = InetAddress.getLocalHost();
			String hostIp = address.getHostAddress();
			Host host = Host.builder().hostIp(hostIp).port(StringUtil.buildPort(taskId)).build();//
			context.setHost(host);
		} catch (Exception e) {
			log.error("主机信息错误", e);
			LoggerUtil.exit(JvmStatus.s15);
		}

		log.info("----------------------task配置信息-------------------------------------");
		final ITaskConf taskConf = new ZookeeperImpl();
		try {
			taskConf.init(taskId);
			taskConf.buildTask();
			// 20190613 把task配置存入内存，所有的插件都可以使用
			JSONObject taskJson = ZkClient.getInst().getZkData(ZkPath.tasks.getPath(taskId));
			taskJson.put("simple", "true");// TODO 测试
			Conf.overJson(taskJson);
			Properties props = new Properties();
			Conf.overProp(props);

			taskConf.buildPos();
			// 20190613设置好col
			Map<String, SortedSet<ColHis>> buildCols = ZkUtil.buildCols(Main.context.buildInstallName(),
					Main.context.getTask());
			Main.context.setColsMap(buildCols);
			if (context.getTask() == null) {
				log.error("----------------------没有配置task:[{}]，不能启动监听-------------------------------------", taskId);
				LoggerUtil.exit(JvmStatus.s15);
			}
			if (taskConf.checkHasPos() && context.getParsePos() == null) {
				log.error("----------------------位点信息格式有问题，需要检查pos:[{}]， 不能启动监听-------------------------------------",
						taskId);
				LoggerUtil.exit(JvmStatus.s15);
			}
		} catch (Exception e) {
			log.error("组装task及相关信息失败，请确认task：" + taskId + "是否在ops上已配置成功。", e);
			log.error("aaa");
			try {
				Thread.sleep(50);
			} catch (InterruptedException e1) {
			}
			Runtime.getRuntime().exit(15);
		}

		log.info("----------------------启动jmx-------------------------------------");
		try {
			initMbean(lock, (ZookeeperImpl) taskConf);// 启动jxmx
		} catch (Exception e) {
			log.error("启动jmx错误", e);
			LoggerUtil.exit(JvmStatus.s15);
		}
		log.info("----------------------启动生产者-------------------------------------");
		IProducer producer = null;
		try {
			producer = new DisruptorProducer(false);
		} catch (Throwable e) {
			log.error("启动必要的生产者失败", e);
			LoggerUtil.exit(JvmStatus.s15);
		}

		addTimer(taskConf);
		addShutdownHook(taskConf);

		ConfUtil.printlnASCII();

		log.info("----------------------启动正式的解析主程序-------------------------------------");
		final ParseLogOnline reader = new ParseLogOnline(producer);
		try {
			reader.read();
		} finally {
			log.error("异常退出，请检查相关日志再启动");
			LoggerUtil.exit(JvmStatus.s15);
		}
	}

	private static void addTimer(final ITaskConf taskConf) {
		ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
		// 第二个参数为首次执行的延时时间，第三个参数为定时执行的间隔时间
		service.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				updatePosAndCount(taskConf);
			}
		}, 10, 1, TimeUnit.SECONDS);
	}

	private static void addShutdownHook(final ITaskConf taskConf) {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				log.info("----------------------执行关闭进程 钩子开始-------------------------------------");
				updatePosAndCount(taskConf);
				log.info("----------------------执行关闭进程 钩子完成-------------------------------------");
			}
		});
	}

	private static long prePos;// 减少位点的重复提交

	private static void updatePosAndCount(final ITaskConf taskConf) {
		try {
			if (context.getPos() == null || (context.getInitPos() != null
					&& context.getPos().getGtids().equals(context.getInitPos().getGtids()))) {// 做主备时且没有往下走，需保留原来位点
																								// TODO
																								// gtid判断相等？
				return;
			}

			if (context.getPos().getPos() != 0 && StringUtil.isNotNull(context.getPos().getGtids())) {

				// log.info("lastPos:{},curPos:{},parseFileName:{},curFileName:{}",
				// context.getLastPos(),
				// context.getPos().getPos(),
				// context.getParsePos().getFileName(),
				// context.getPos().getFileName());
				if (context.getLastPos() == context.getPos().getPos()) {// 如果换文件也几乎不可能出现位点一样的情况
																		// &&
																		// context.getParsePos().getFileName().equals(context.getPos().getFileName())一段时间没有提交的位点
					if (context.getParsePos().getTime() != 0 && prePos != context.getParsePos().getPos()) {
						taskConf.updatePos(context.getParsePos());
						prePos = context.getParsePos().getPos();
					}
					// log.info("update ParsePos");
				} else {
					if (context.getPos().getTime() != 0 && prePos != context.getPos().getPos()) {
						taskConf.updatePos(context.getPos());
						prePos = context.getPos().getPos();
					}
					// log.info("update CurPos");
				}
				// log.info("1、pos submit sucess, file：[{}], pos:[{}]",
				// context.getPos().getFileName(),
				// context.getPos().getPos());
			}
		} catch (Exception e) {
			// log.error("1、pos submit error", e);
		}
		// 更新计数
		try {
			Count.CountBuilder build = Count.builder().insertNum(metric.meter_sender_event_add.getCount());
			build.updateNum(metric.meter_sender_event_update.getCount());
			build.deleteNum(metric.meter_sender_event_del.getCount());
			build.filterNum(metric.meter_sender_event_filter.getCount());
			build.allPack(metric.meter_parser_pack_all.getCount());
			build.parserPack(metric.meter_parser_pack_row.getCount());
			build.parserEvent(metric.meter_parser_event.getCount());
			build.sendEvent(metric.meter_sender_event.getCount());
			build.ringbuffPack(metric.counter_ringbuff_pack.getCount());
			build.ringbuffEvent(metric.counter_ringbuff_event.getCount());
			taskConf.updateCount(build.build());
		} catch (Exception e) {
			// TODO: handle exception
		}

	}

	private static void initMbean(InterProcessMutex lock, ZookeeperImpl taskConf) throws InstanceAlreadyExistsException,
			MBeanRegistrationException, NotCompliantMBeanException, MalformedObjectNameException {
		BinlogControl control = new BinlogControl(lock, taskConf);
		// control.setLock(lock);
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		mbs.registerMBean(control,
				new ObjectName("net.wicp.tams.duckula:service=Task,name=" + Conf.get("duckula.task.mbean.beanname")));
		log.info("----------------------MBean注册成功-------------------------------------");
	}

}
