import { useEffect, useState } from 'react';
import axios from 'axios';
import {
  CurrencyDollarIcon,
  PhoneIcon,
  ClockIcon,
  ExclamationTriangleIcon,
  UserGroupIcon,
  XCircleIcon,
  ArrowDownIcon,
  ArrowUpIcon,
} from '@heroicons/react/24/outline';
import {
  PieChart, Pie, Cell,
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, Legend, ResponsiveContainer,
} from 'recharts';

// ─── Types ────────────────────────────────────────────────────────────────────

interface TelephonyTypeCost  { telephonyTypeName: string; cost: number; incomingCalls: number; outgoingCalls: number; }
interface CostCenterUsage    { costCenterName: string;    cost: number; }
interface SubdivisionSummary { subdivisionName: string;   totalCost: number; totalCalls: number; }
interface EmployeeSummary    { employeeName: string; extension: string; callCount: number; totalDuration: number; totalCost: number; }

interface DashboardData {
  totalCost:             number;
  totalCalls:            number;
  totalIncomingCalls:    number;
  totalOutgoingCalls:    number;
  totalDurationSeconds:  number;
  averageCostPerMinute:  number;
  processingFailures:    number;
  unassignedCalls:       number;
  costByTelephonyType:   TelephonyTypeCost[];
  topCostCenters:        CostCenterUsage[];
  topSubdivisions:       SubdivisionSummary[];
  topEmployees:          EmployeeSummary[];
}

// ─── Constants ────────────────────────────────────────────────────────────────

const PIE_COLORS = ['#3b82f6','#10b981','#f59e0b','#ef4444','#8b5cf6','#06b6d4','#f97316'];

const KPI_STYLES: Record<string, { border: string; valueCls: string; iconBg: string }> = {
  emerald: { border: 'border-emerald-100', valueCls: 'text-emerald-700', iconBg: 'bg-emerald-50'  },
  blue:    { border: 'border-blue-100',    valueCls: 'text-blue-700',    iconBg: 'bg-blue-50'     },
  purple:  { border: 'border-purple-100',  valueCls: 'text-purple-700',  iconBg: 'bg-purple-50'   },
  amber:   { border: 'border-amber-100',   valueCls: 'text-amber-700',   iconBg: 'bg-amber-50'    },
  orange:  { border: 'border-orange-100',  valueCls: 'text-orange-700',  iconBg: 'bg-orange-50'   },
  red:     { border: 'border-red-100',     valueCls: 'text-red-700',     iconBg: 'bg-red-50'      },
  sky:     { border: 'border-sky-100',     valueCls: 'text-sky-700',     iconBg: 'bg-sky-50'      },
  teal:    { border: 'border-teal-100',    valueCls: 'text-teal-700',    iconBg: 'bg-teal-50'     },
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

const fmt$ = (n: number) =>
  n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const fmtK = (n: number) =>
  n >= 1_000_000 ? `$${(n / 1_000_000).toFixed(1)}M`
  : n >= 1_000   ? `$${(n / 1_000).toFixed(0)}k`
  : `$${n.toFixed(0)}`;

const fmtNum = (n: number) =>
  n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M`
  : n >= 1_000   ? `${(n / 1_000).toFixed(0)}k`
  : n.toLocaleString();

const fmtDuration = (s: number) => {
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
};

const monthName = (value: string) => {
  const [y, m] = value.split('-');
  return new Date(+y, +m - 1).toLocaleString(undefined, { month: 'long', year: 'numeric' });
};

// ─── Main component ───────────────────────────────────────────────────────────

export default function App() {
  const now = new Date();
  const pad  = (n: number) => n.toString().padStart(2, '0');
  const [selectedMonth, setSelectedMonth] = useState(`${now.getFullYear()}-${pad(now.getMonth() + 1)}`);
  const [data, setData]       = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        const [y, m] = selectedMonth.split('-').map(Number);
        const lastDay = new Date(y, m, 0).getDate();
        const res = await axios.get('/api/dashboard/overview', {
          params: {
            startDate: `${selectedMonth}-01T00:00:00`,
            endDate:   `${selectedMonth}-${pad(lastDay)}T23:59:59`,
          },
          headers: { 'X-Tenant-Id': 'colsanitas', 'X-Username': 'admin' },
        });
        if (!cancelled) setData(res.data);
      } catch {
        if (!cancelled) setError('Failed to fetch dashboard data.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    fetchData();
    return () => { cancelled = true; };
  }, [selectedMonth]);

  // Build incoming/outgoing grouped bar data from telephony types
  const callDirectionData = (data?.costByTelephonyType ?? []).map(t => ({
    name: t.telephonyTypeName,
    Incoming: t.incomingCalls ?? 0,
    Outgoing: t.outgoingCalls ?? 0,
  }));

  return (
    <div className="min-h-screen bg-slate-50 text-gray-900 font-sans">

      {/* ── Topbar ─────────────────────────────────────────────────── */}
      <header className="bg-white border-b border-gray-200 px-8 py-4 sticky top-0 z-10">
        <div className="max-w-7xl mx-auto flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
          <div>
            <h1 className="text-xl font-bold text-gray-900 tracking-tight">Telephony Dashboard</h1>
            <p className="text-xs text-gray-400 mt-0.5">{data ? monthName(selectedMonth) : 'Loading…'}</p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {/* Month picker */}
            <div className="flex items-center gap-1.5 bg-gray-50 border border-gray-200 rounded-lg px-3 py-1.5 text-sm">
              <span className="text-gray-500 font-medium">Month</span>
              <input
                type="month"
                value={selectedMonth}
                onChange={e => setSelectedMonth(e.target.value)}
                className="bg-transparent font-semibold text-gray-900 border-none outline-none cursor-pointer"
              />
            </div>
            {data && data.unassignedCalls > 0 && (
              <Badge color="amber" icon={<ExclamationTriangleIcon className="h-3.5 w-3.5" />}>
                {data.unassignedCalls.toLocaleString()} unassigned
              </Badge>
            )}
            {data && data.processingFailures > 0 && (
              <Badge color="red" icon={<XCircleIcon className="h-3.5 w-3.5" />}>
                {data.processingFailures} {data.processingFailures === 1 ? 'failure' : 'failures'}
              </Badge>
            )}
          </div>
        </div>
      </header>

      {/* ── Body ────────────────────────────────────────────────────── */}
      <main className="max-w-7xl mx-auto px-6 py-7 space-y-6">

        {error && (
          <div className="bg-red-50 text-red-600 border border-red-200 rounded-xl px-4 py-3 text-sm">{error}</div>
        )}

        {loading ? (
          <div className="h-80 flex items-center justify-center text-gray-400 text-base animate-pulse">
            Loading…
          </div>
        ) : data && (
          <>
            {/* ── KPI row ──────────────────────────────────────────── */}
            <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-3">
              <div className="col-span-2 sm:col-span-2">
                <KpiCard accent="emerald" title="Total Cost"    value={`$${fmt$(data.totalCost)}`}                icon={<CurrencyDollarIcon className="h-5 w-5 text-emerald-600" />} />
              </div>
              <div className="col-span-2 sm:col-span-2">
                <KpiCard accent="blue"    title="Total Calls"   value={data.totalCalls.toLocaleString()}          icon={<PhoneIcon className="h-5 w-5 text-blue-600" />} />
              </div>
              <div className="col-span-2 sm:col-span-2">
                <KpiCard accent="purple"  title="Duration"      value={fmtDuration(data.totalDurationSeconds)}    icon={<ClockIcon className="h-5 w-5 text-purple-600" />} />
              </div>
              <div className="col-span-2 sm:col-span-2">
                <KpiCard accent="amber"   title="Avg / Min"     value={`$${data.averageCostPerMinute.toFixed(4)}`} icon={<CurrencyDollarIcon className="h-5 w-5 text-amber-600" />} />
              </div>
              <div className="col-span-2 sm:col-span-2">
                <KpiCard accent="sky"     title="Incoming"      value={fmtNum(data.totalIncomingCalls)}           icon={<ArrowDownIcon className="h-5 w-5 text-sky-600" />} />
              </div>
              <div className="col-span-2 sm:col-span-2">
                <KpiCard accent="teal"    title="Outgoing"      value={fmtNum(data.totalOutgoingCalls)}           icon={<ArrowUpIcon className="h-5 w-5 text-teal-600" />} />
              </div>
              <div className="col-span-2 sm:col-span-2">
                <KpiCard accent="orange"  title="Unassigned"    value={data.unassignedCalls.toLocaleString()}     icon={<UserGroupIcon className="h-5 w-5 text-orange-500" />} warn={data.unassignedCalls > 0} />
              </div>
              <div className="col-span-2 sm:col-span-2">
                <KpiCard accent="red"     title="Failures"      value={data.processingFailures.toLocaleString()}  icon={<XCircleIcon className="h-5 w-5 text-red-500" />} warn={data.processingFailures > 0} />
              </div>
            </div>

            {/* ── Cost by type + Calls direction ───────────────────── */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <Card title="Cost by Call Type">
                {data.costByTelephonyType.length > 0 ? (
                  <div className="h-64">
                    <ResponsiveContainer width="100%" height="100%">
                      <PieChart>
                        <Pie
                          data={data.costByTelephonyType}
                          cx="50%" cy="50%"
                          innerRadius={55} outerRadius={85}
                          paddingAngle={3}
                          dataKey="cost"
                          nameKey="telephonyTypeName"
                        >
                          {data.costByTelephonyType.map((_, i) => (
                            <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                          ))}
                        </Pie>
                        <Tooltip formatter={(v: unknown) => [`$${fmt$((v as number) ?? 0)}`, 'Cost']} contentStyle={{ fontSize: 12, borderRadius: 8 }} />
                        <Legend verticalAlign="bottom" height={40} wrapperStyle={{ fontSize: 12 }} />
                      </PieChart>
                    </ResponsiveContainer>
                  </div>
                ) : <EmptyState />}
              </Card>

              <Card title="Incoming vs Outgoing by Call Type">
                {callDirectionData.length > 0 ? (
                  <div className="h-64">
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={callDirectionData} layout="vertical" margin={{ top: 0, right: 20, left: 90, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#f0f0f0" />
                        <XAxis type="number" tickFormatter={v => fmtNum(v as number)} tick={{ fontSize: 11 }} axisLine={false} tickLine={false} />
                        <YAxis type="category" dataKey="name" tick={{ fontSize: 11 }} width={85} axisLine={false} tickLine={false} />
                        <Tooltip formatter={(v: unknown, n: unknown) => [(v as number).toLocaleString(), n as string]} contentStyle={{ fontSize: 12, borderRadius: 8 }} />
                        <Legend wrapperStyle={{ fontSize: 12 }} />
                        <Bar dataKey="Incoming" fill="#93c5fd" radius={[0, 0, 0, 0]} stackId="dir" />
                        <Bar dataKey="Outgoing" fill="#3b82f6" radius={[0, 3, 3, 0]} stackId="dir" />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                ) : <EmptyState />}
              </Card>
            </div>

            {/* ── Top Cost Centers + Top Subdivisions ──────────────── */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <Card title="Top Cost Centers">
                {data.topCostCenters.length > 0 ? (
                  <div className="h-64">
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={data.topCostCenters} layout="vertical" margin={{ top: 0, right: 20, left: 90, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#f0f0f0" />
                        <XAxis type="number" tickFormatter={fmtK} tick={{ fontSize: 11 }} axisLine={false} tickLine={false} />
                        <YAxis type="category" dataKey="costCenterName" tick={{ fontSize: 11 }} width={85} axisLine={false} tickLine={false} />
                        <Tooltip formatter={(v: unknown) => [`$${fmt$((v as number) ?? 0)}`, 'Cost']} contentStyle={{ fontSize: 12, borderRadius: 8 }} />
                        <Bar dataKey="cost" fill="#3b82f6" radius={[0, 4, 4, 0]} />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                ) : <EmptyState />}
              </Card>

              <Card title="Top Subdivisions">
                {data.topSubdivisions.length > 0 ? (
                  <div className="h-64">
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={data.topSubdivisions} layout="vertical" margin={{ top: 0, right: 20, left: 90, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#f0f0f0" />
                        <XAxis type="number" tickFormatter={fmtK} tick={{ fontSize: 11 }} axisLine={false} tickLine={false} />
                        <YAxis type="category" dataKey="subdivisionName" tick={{ fontSize: 11 }} width={85} axisLine={false} tickLine={false} />
                        <Tooltip formatter={(v: unknown) => [`$${fmt$((v as number) ?? 0)}`, 'Cost']} contentStyle={{ fontSize: 12, borderRadius: 8 }} />
                        <Bar dataKey="totalCost" fill="#10b981" radius={[0, 4, 4, 0]} />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                ) : <EmptyState />}
              </Card>
            </div>

            {/* ── Top Employees ─────────────────────────────────────── */}
            <Card title="Top Employees by Cost">
              {data.topEmployees.length > 0 ? (
                <div className="overflow-auto">
                  <table className="w-full text-sm border-separate border-spacing-0">
                    <thead>
                      <tr className="text-left text-xs text-gray-400 uppercase tracking-wide border-b border-gray-100">
                        <th className="pb-2 pr-3 font-medium">#</th>
                        <th className="pb-2 pr-3 font-medium">Employee</th>
                        <th className="pb-2 pr-3 font-medium">Extension</th>
                        <th className="pb-2 pr-3 font-medium text-right">Calls</th>
                        <th className="pb-2 pr-3 font-medium text-right">Duration</th>
                        <th className="pb-2 font-medium text-right">Total Cost</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.topEmployees.map((emp, i) => (
                        <tr key={i} className="border-b border-gray-50 hover:bg-slate-50 transition-colors">
                          <td className="py-2 pr-3 text-gray-400 tabular-nums">{i + 1}</td>
                          <td className="py-2 pr-3 font-medium text-gray-800">{emp.employeeName ?? '—'}</td>
                          <td className="py-2 pr-3 text-gray-400 font-mono text-xs">{emp.extension ?? '—'}</td>
                          <td className="py-2 pr-3 text-right tabular-nums">{(emp.callCount ?? 0).toLocaleString()}</td>
                          <td className="py-2 pr-3 text-right tabular-nums text-gray-400 text-xs">{fmtDuration(emp.totalDuration ?? 0)}</td>
                          <td className="py-2 text-right tabular-nums font-semibold text-blue-700">${fmt$(emp.totalCost ?? 0)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : <EmptyState />}
            </Card>
          </>
        )}
      </main>
    </div>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function KpiCard({ title, value, icon, accent, warn }: {
  title: string; value: string; icon: React.ReactNode; accent: keyof typeof KPI_STYLES; warn?: boolean;
}) {
  const s = KPI_STYLES[accent];
  return (
    <div className={`bg-white rounded-xl border ${warn ? 'border-orange-200' : s.border} p-4 flex items-center gap-3 shadow-sm hover:shadow-md transition-shadow h-full`}>
      <div className={`p-2 rounded-lg shrink-0 ${warn ? 'bg-orange-100' : s.iconBg}`}>{icon}</div>
      <div className="min-w-0">
        <p className="text-xs font-medium text-gray-500 truncate">{title}</p>
        <p className={`text-lg font-bold tabular-nums leading-tight ${warn ? 'text-orange-700' : s.valueCls}`}>{value}</p>
      </div>
    </div>
  );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-5">
      <h3 className="text-sm font-semibold text-gray-700 mb-4">{title}</h3>
      {children}
    </div>
  );
}

function Badge({ color, icon, children }: { color: 'red' | 'amber'; icon: React.ReactNode; children: React.ReactNode }) {
  const cls = color === 'red'
    ? 'bg-red-50 text-red-700 border-red-200'
    : 'bg-amber-50 text-amber-700 border-amber-200';
  return (
    <span className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-lg border text-xs font-medium ${cls}`}>
      {icon}{children}
    </span>
  );
}

function EmptyState() {
  return <div className="h-20 flex items-center justify-center text-sm text-gray-400">No data for this period</div>;
}
